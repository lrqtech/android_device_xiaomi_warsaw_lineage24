#include "AuthenticatedUnixChannel.h"

#include <array>
#include <chrono>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <selinux/selinux.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <thread>
#include <unistd.h>

namespace warsaw::cameramind {
namespace {
using Status = AuthenticatedUnixChannel::Status;
using namespace std::chrono_literals;

std::string frame(int32_t session, std::string_view json) {
    std::string out(12, '\0');
    out[0] = 64;
    const uint32_t fields[] = {static_cast<uint32_t>(session), static_cast<uint32_t>(json.size())};
    for (size_t n = 0; n < 2; ++n) {
        for (size_t b = 0; b < 4; ++b) out[4 + n * 4 + b] = (fields[n] >> ((3 - b) * 8)) & 255;
    }
    out.append(json);
    return out;
}

size_t fdCount() {
    DIR* directory = ::opendir("/proc/self/fd");
    if (directory == nullptr) return SIZE_MAX;
    size_t count = 0;
    while (const dirent* entry = ::readdir(directory)) {
        if (entry->d_name[0] != '.') ++count;
    }
    ::closedir(directory);
    return count;
}

class UnixChannelTest : public ::testing::Test {
protected:
    void SetUp() override {
        ASSERT_EQ(1, is_selinux_enabled()) << "Requires SELinux peer labels in either global mode";
        ASSERT_EQ(0, ::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets));
        const timeval timeout{2, 0};
        ASSERT_EQ(0, ::setsockopt(sockets[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
        ASSERT_EQ(0, ::setsockopt(sockets[1], SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)));
        char* context = nullptr;
        ASSERT_EQ(0, getcon(&context));
        ASSERT_NE(nullptr, context);
        required.securityContext = context;
        freecon(context);
    }
    void TearDown() override {
        channel.reset();
        for (int fd : sockets) if (fd >= 0) ::close(fd);
    }
    void adopt() {
        const int fd = sockets[0];
        sockets[0] = -1;
        channel = AuthenticatedUnixChannel::adoptConnected(fd, required, &result);
        ASSERT_NE(nullptr, channel.get());
        ASSERT_EQ(Status::Ok, result.status);
    }
    void sendPeer(std::string_view data) {
        ASSERT_EQ(static_cast<ssize_t>(data.size()),
                  ::send(sockets[1], data.data(), data.size(), MSG_NOSIGNAL));
    }
    int sockets[2]{-1, -1};
    CameraMindPeerRequirement required{::getuid(), {}};
    AuthenticatedUnixChannel::Result result{Status::InvalidArgument};
    std::unique_ptr<AuthenticatedUnixChannel> channel;
};

TEST_F(UnixChannelTest, RejectsDifferentUidAndClosesOwnedDescriptor) {
    required.uid = required.uid == 0 ? 1 : 0;
    const int fd = sockets[0];
    sockets[0] = -1;
    channel = AuthenticatedUnixChannel::adoptConnected(fd, required, &result);
    EXPECT_EQ(nullptr, channel);
    EXPECT_EQ(Status::Identity, result.status);
    EXPECT_EQ(-1, ::fcntl(fd, F_GETFD));
}

TEST_F(UnixChannelTest, RejectsContextMismatchInsteadOfUsingUidAlone) {
    required.securityContext += ":wrong";
    const int fd = sockets[0];
    sockets[0] = -1;
    channel = AuthenticatedUnixChannel::adoptConnected(fd, required, &result);
    EXPECT_EQ(nullptr, channel);
    EXPECT_EQ(Status::Identity, result.status);
}

TEST_F(UnixChannelTest, RejectsUnconnectedAndDatagramDescriptors) {
    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    ASSERT_GE(fd, 0);
    EXPECT_EQ(nullptr, AuthenticatedUnixChannel::adoptConnected(fd, required, &result));
    EXPECT_EQ(-1, ::fcntl(fd, F_GETFD));
    int datagrams[2];
    ASSERT_EQ(0, ::socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, datagrams));
    EXPECT_EQ(nullptr, AuthenticatedUnixChannel::adoptConnected(datagrams[0], required, &result));
    ::close(datagrams[1]);
    EXPECT_EQ(Status::InvalidArgument, result.status);
}

TEST_F(UnixChannelTest, IdlePreservesOutputAndConnection) {
    adopt();
    ASSERT_NE(nullptr, channel);
    CameraMindReceivedFrame out;
    out.rawJson = "unchanged";
    EXPECT_EQ(Status::Idle, channel->readFrame(&out, 20ms).status);
    EXPECT_EQ("unchanged", out.rawJson);
    EXPECT_FALSE(channel->cancelled());
}

TEST_F(UnixChannelTest, DoesNotConsumeTheNextCoalescedFrameOrInferSessionContinuity) {
    adopt();
    ASSERT_NE(nullptr, channel);
    const auto first = frame(-1, R"({"what":2006,"data":{"reportExposureTime":"1"}})");
    const auto second = frame(-1, R"({"what":2016,"data":{"exp-times":[20]}})");
    sendPeer(first + second);
    CameraMindReceivedFrame out;
    auto got = channel->readFrame(&out, 1s);
    EXPECT_EQ(Status::Ok, got.status);
    EXPECT_EQ(first.size(), got.bytes);
    EXPECT_EQ(-1, out.wireSessionId);
    EXPECT_EQ(CameraMindControlDisposition::Decoded, out.controlDisposition);
    ASSERT_TRUE(out.command.has_value());
    EXPECT_TRUE(out.command->reportFlags.reportExposureTime);
    got = channel->readFrame(&out, 1s);
    EXPECT_EQ(Status::Ok, got.status);
    EXPECT_EQ(second.size(), got.bytes);
    ASSERT_TRUE(out.command.has_value());
    ASSERT_EQ(1U, out.command->thresholdsMillis.size());
    EXPECT_EQ(20, out.command->thresholdsMillis[0]);
}

TEST_F(UnixChannelTest, SemanticInvalidControlDoesNotPretendToBeAnAppliedCommand) {
    adopt();
    ASSERT_NE(nullptr, channel);
    sendPeer(frame(1, R"({"what":2016,"data":{"exp-times":[20.5]}})"));
    CameraMindReceivedFrame out;
    EXPECT_EQ(Status::Ok, channel->readFrame(&out, 1s).status);
    EXPECT_EQ(CameraMindControlDisposition::Invalid, out.controlDisposition);
    EXPECT_FALSE(out.command.has_value());
}

TEST_F(UnixChannelTest, PartialFrameTimesOutAndCancelsWithoutMutatingOutput) {
    adopt();
    ASSERT_NE(nullptr, channel);
    sendPeer(std::string(1, 64));
    CameraMindReceivedFrame out;
    out.rawJson = "unchanged";
    const auto start = std::chrono::steady_clock::now();
    const auto got = channel->readFrame(&out, 1s);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    EXPECT_EQ(Status::Deadline, got.status);
    EXPECT_EQ(1U, got.bytes);
    EXPECT_EQ("unchanged", out.rawJson);
    EXPECT_TRUE(channel->cancelled());
    EXPECT_GE(elapsed, 1900ms);
    EXPECT_LT(elapsed, 5s);
}

TEST_F(UnixChannelTest, TruncatedEofIsDistinctFromCleanEof) {
    adopt();
    ASSERT_NE(nullptr, channel);
    sendPeer(std::string(1, 64));
    ASSERT_EQ(0, ::shutdown(sockets[1], SHUT_WR));
    CameraMindReceivedFrame out;
    EXPECT_EQ(Status::Protocol, channel->readFrame(&out, 1s).status);
}

TEST_F(UnixChannelTest, CleanEofCancelsChannel) {
    adopt();
    ASSERT_NE(nullptr, channel);
    ASSERT_EQ(0, ::shutdown(sockets[1], SHUT_WR));
    CameraMindReceivedFrame out;
    EXPECT_EQ(Status::Eof, channel->readFrame(&out, 1s).status);
    EXPECT_TRUE(channel->cancelled());
}

TEST_F(UnixChannelTest, RejectsAndClosesReceivedDescriptors) {
    adopt();
    ASSERT_NE(nullptr, channel);
    const int sentFd = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
    ASSERT_GE(sentFd, 0);
    const auto payload = frame(1, R"({"what":2004})");
    iovec io{const_cast<char*>(payload.data()), payload.size()};
    alignas(cmsghdr) std::array<unsigned char, CMSG_SPACE(sizeof(int))> control{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control.data();
    message.msg_controllen = control.size();
    cmsghdr* c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(c), &sentFd, sizeof(sentFd));
    const size_t before = fdCount();
    EXPECT_NE(SIZE_MAX, before);
    EXPECT_EQ(static_cast<ssize_t>(payload.size()), ::sendmsg(sockets[1], &message, MSG_NOSIGNAL));
    CameraMindReceivedFrame out;
    EXPECT_EQ(Status::Ancillary, channel->readFrame(&out, 1s).status);
    EXPECT_EQ(before, fdCount());
    ::close(sentFd);
}

TEST_F(UnixChannelTest, CancellationWakesReaderWithoutClosingOrReusingItsFd) {
    adopt();
    ASSERT_NE(nullptr, channel);
    AuthenticatedUnixChannel::Result got{Status::InvalidArgument};
    std::thread reader([&] {
        CameraMindReceivedFrame out;
        got = channel->readFrame(&out, 60s);
    });
    std::this_thread::sleep_for(20ms);
    channel->cancel();
    reader.join();
    EXPECT_EQ(Status::Cancelled, got.status);
}

TEST_F(UnixChannelTest, WritesExactlyOneFrameAndRejectsWrongLengthWithoutSending) {
    adopt();
    ASSERT_NE(nullptr, channel);
    const auto payload = frame(1001, R"({"what":1005,"data":{"my_name":"cameraserver"}})");
    EXPECT_EQ(Status::InvalidArgument, channel->sendFrame(payload + "x").status);
    EXPECT_FALSE(channel->cancelled());
    EXPECT_EQ(Status::Ok, channel->sendFrame(payload).status);
    std::string got(payload.size(), '\0');
    EXPECT_EQ(static_cast<ssize_t>(got.size()), ::recv(sockets[1], got.data(), got.size(), MSG_WAITALL));
    EXPECT_EQ(payload, got);
}

}
}
