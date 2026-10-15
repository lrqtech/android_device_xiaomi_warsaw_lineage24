#ifndef _MSM_DRM_AIQE_H_
#define _MSM_DRM_AIQE_H_

#include <linux/types.h>

#define AIQE_MDNIE_SUPPORTED
#define AIQE_MDNIE_PARAM_LEN 118
struct drm_msm_mdnie {
	__u64 flags;
	__u32 param[AIQE_MDNIE_PARAM_LEN];
};

struct drm_msm_mdnie_art {
	__u64 flags;
	__u32 param;
};

struct drm_msm_mdnie_art_done {
	__u32 art_done;
};

#define AIQE_SSRC_SUPPORTED
#define AIQE_SSRC_PARAM_LEN 16
struct drm_msm_ssrc_config {
	__u32 flags;
	__u32 config[AIQE_SSRC_PARAM_LEN];
};

#define AIQE_SSRC_DATA_LEN 5128
struct drm_msm_ssrc_data {
	__u32 data_size;
	__u32 data[AIQE_SSRC_DATA_LEN];
};

#define AIQE_COPR_PARAM_LEN 17
struct drm_msm_copr {
	__u64 flags;
	__u32 param[AIQE_COPR_PARAM_LEN];
};

#define AIQE_COPR_STATUS_LEN 10
struct drm_msm_copr_status {
	__u32 status[AIQE_COPR_STATUS_LEN];
};

#define AIQE_AI_SCALER_PARAM_LEN 485
struct drm_msm_ai_scaler {
	__u64 flags;
	__u32 config;
	__u32 src_w;
	__u32 src_h;
	__u32 dst_w;
	__u32 dst_h;
	__u32 param[AIQE_AI_SCALER_PARAM_LEN];
};

#define AIQE_ABC_SUPPORTED
#define AIQE_ABC_PARAM_LEN 44
#define AIQE_ABC_SRC_SEL_DMA1 1
#define AIQE_ABC_SRC_SEL_DMA3 3
struct drm_msm_abc {
	__u64 flags;
	__u32 src_sel;
	__u32 param[AIQE_ABC_PARAM_LEN];
};

#endif
