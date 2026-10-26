#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import subprocess,json,hashlib
import argparse, tempfile
parser=argparse.ArgumentParser(description="Run the earlyBootEnded host regression tests with modeled HAL responses.")
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6])
parser.add_argument('--report-dir',type=Path)
args=parser.parse_args()
R=args.source.resolve();S=R/'system/security/keystore2/src'
workspace=tempfile.TemporaryDirectory(prefix='keystore-notification-tests-')
T=Path(workspace.name);N=args.report_dir or T
N.mkdir(parents=True,exist_ok=True)
rust=R/'prebuilts/rust-toolchain/linux-x86/1.93.1/bin/rustc'
s=(S/'maintenance.rs').read_text()
def item(start):
 a=s.index(start);b=s.index('{',a);depth=1;i=b+1
 while depth:
  depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
adapter=s[s.index('static TEE_EARLY_BOOT:'):s.index('/// This struct is defined to implement')]
method=item('    fn notify_early_boot_ended()')
harness=r'''
mod anyhow { pub type Error = super::WrappedError; }
use std::any::Any;
use std::collections::{BTreeMap,VecDeque};
use std::sync::{Mutex,LazyLock};
use early_boot_notification::{BootNotification,Notification};
macro_rules! info { ($($a:tt)*) => { { let _ = format!($($a)*); } }; }
macro_rules! error { ($($a:tt)*) => { { let _ = format!($($a)*); } }; }
#[derive(Debug,Clone,Copy,PartialEq,Eq)] struct SecurityLevel(i32);
impl SecurityLevel { const TRUSTED_ENVIRONMENT: Self = Self(1); const STRONGBOX: Self = Self(2); }
#[derive(Debug,Clone,Copy,PartialEq,Eq)] pub struct ErrorCode(i32);
impl ErrorCode {
 const SECURE_HW_COMMUNICATION_FAILED: Self = Self(-49);
 const HARDWARE_TYPE_UNAVAILABLE: Self = Self(-68);
}
#[derive(Debug,Clone,PartialEq,Eq)] pub enum Error { Km(ErrorCode), Other }
#[derive(Debug,Clone,PartialEq,Eq)] pub struct WrappedError(Error);
impl std::fmt::Display for WrappedError { fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result { write!(f,"{:?}",self.0) } }
impl WrappedError { fn downcast_ref<T:'static>(&self)->Option<&T> { (&self.0 as &dyn Any).downcast_ref() } }
pub type Result<T> = std::result::Result<T,WrappedError>;
#[derive(Default)] struct DeviceCalls { replies: BTreeMap<i32,VecDeque<Result<()>>>, calls:Vec<i32> }
static CALLS: LazyLock<Mutex<DeviceCalls>> = LazyLock::new(||Mutex::new(DeviceCalls::default()));
struct FakeDevice(SecurityLevel);
impl FakeDevice {
 #[allow(non_snake_case)] fn earlyBootEnded(&self)->Result<()> {
  let mut c=CALLS.lock().unwrap(); c.calls.push(self.0.0);
  c.replies.get_mut(&self.0.0).expect("unknown level").pop_front().expect("unexpected notification")
 }
}
struct Maintenance;
impl Maintenance {
 fn call_with_watchdog<F>(level:SecurityLevel,name:&'static str,op:&F,min:Option<i32>)->Result<()>
 where F:Fn(FakeDevice)->Result<()> {
  assert_eq!(name,"earlyBootEnded"); assert_eq!(min,None); op(FakeDevice(level))
 }
 __METHOD__
}
__ADAPTER__
#[path="__CORE__"] mod early_boot_notification;
#[test] fn actual_error_classifier_only_retries_transport_failure() {
 for code in -1001..=1 {assert_eq!(EarlyBootDevice::retryable(&WrappedError(Error::Km(ErrorCode(code)))),code==-49);}
 assert!(!EarlyBootDevice::retryable(&WrappedError(Error::Other)));
}
#[test] fn actual_notification_loop_never_skips_strongbox_and_preserves_first_error() {
 let fail=|code|Err(WrappedError(Error::Km(ErrorCode(code))));
 for (tee,sb,expected) in [
  (Ok(()),Ok(()),Ok(())), (fail(-1000),Ok(()),fail(-1000)),
  (Ok(()),fail(-1),fail(-1)), (fail(-1000),fail(-1),fail(-1000)),
  (Ok(()),fail(-68),Ok(())), (fail(-68),Ok(()),fail(-68)),
  (fail(-1000),fail(-68),fail(-1000)),
 ] {
  *CALLS.lock().unwrap()=DeviceCalls{replies:BTreeMap::from([(1,VecDeque::from([tee])),(2,VecDeque::from([sb]))]),calls:vec![]};
  assert_eq!(Maintenance::notify_early_boot_ended(),expected);
  assert_eq!(CALLS.lock().unwrap().calls,[1,2]);
 }
}
'''.replace('__METHOD__',method).replace('__ADAPTER__',adapter).replace('__CORE__',str(S/'early_boot_notification.rs'))
p=T/'adapter_tests.rs';p.write_text(harness)
r=subprocess.run([str(rust),'--edition=2021','--test',str(p),'-o',str(T/'adapter-tests')],text=True,capture_output=True);print(r.stderr);r.check_returncode()
r=subprocess.run([str(T/'adapter-tests'),'--test-threads=1'],text=True,capture_output=True);print(r.stdout,r.stderr);r.check_returncode()
base=subprocess.check_output(['git','-C',str(R/'system/security'),'show','HEAD:keystore2/src/maintenance.rs'],text=True)
old=base[base.index('    fn early_boot_ended()'):base.index('    /// Spawns a thread to send module info')]
new=s[s.index('    fn early_boot_ended()'):s.index('    fn notify_early_boot_ended()')]
assert old.replace('Maintenance::call_on_all_security_levels("earlyBootEnded", |dev| dev.earlyBootEnded(), None)','Self::notify_early_boot_ended()').strip()==new.strip()
(N/'keystore-adapter-tests.txt').write_text(r.stdout)
(N/'keystore-notification-validation.json').write_text(json.dumps({'host_tests':17,'classification_values':1004,'notification_result_pairs':7,'permission_and_boot_cache_setup_unchanged':True,'actual_adapter_source_compiled_against_test_doubles':True,'full_android_build':False,'phone_validation':False,'maintenance_sha256':hashlib.sha256((S/'maintenance.rs').read_bytes()).hexdigest(),'core_sha256':hashlib.sha256((S/'early_boot_notification.rs').read_bytes()).hexdigest()},indent=2)+'\n')
