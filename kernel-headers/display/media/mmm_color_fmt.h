#ifndef __MMM_COLOR_FMT_INFO_H__
#define __MMM_COLOR_FMT_INFO_H__

#define INTERLACE_WIDTH_MAX 1920
#define INTERLACE_HEIGHT_MAX 1920
#define INTERLACE_MB_PER_FRAME_MAX ((1920*1088)/256)

#ifndef MMM_COLOR_FMT_ALIGN
#define MMM_COLOR_FMT_ALIGN(__sz, __align) (((__align) & ((__align) - 1)) ?\
	((((__sz) + (__align) - 1) / (__align)) * (__align)) :\
	(((__sz) + (__align) - 1) & (~((__align) - 1))))
#endif

#ifndef MMM_COLOR_FMT_ROUNDUP
#define MMM_COLOR_FMT_ROUNDUP(__sz, __r) (((__sz) + ((__r) - 1)) / (__r))
#endif

enum mmm_color_fmts {
	MMM_COLOR_FMT_NV12,
	MMM_COLOR_FMT_NV21,
	MMM_COLOR_FMT_NV12_UBWC,
	MMM_COLOR_FMT_NV12_BPP10_UBWC,
	MMM_COLOR_FMT_RGBA8888,
	MMM_COLOR_FMT_RGBA8888_UBWC,
	MMM_COLOR_FMT_RGBA1010102_UBWC,
	MMM_COLOR_FMT_RGB565_UBWC,
	MMM_COLOR_FMT_P010_UBWC,
	MMM_COLOR_FMT_P010,
	MMM_COLOR_FMT_P010_512,
	MMM_COLOR_FMT_NV12_512,
	MMM_COLOR_FMT_RGBA16161616F_UBWC,
	MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC,
	MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC,
	MMM_COLOR_FMT_NV124R_UBWC,
};

static inline unsigned int MMM_COLOR_FMT_Y_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	unsigned int alignment, stride = 0;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_512:
		alignment = 512;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV12:
	case MMM_COLOR_FMT_NV21:
	case MMM_COLOR_FMT_NV12_UBWC:
		alignment = 128;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV124R_UBWC:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width, 192);
		stride = MMM_COLOR_FMT_ALIGN(stride * 4/3, alignment);
		break;
	case MMM_COLOR_FMT_P010_UBWC:
	case MMM_COLOR_FMT_P010:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width * 2, alignment);
		break;
	case MMM_COLOR_FMT_P010_512:
		alignment = 512;
		stride = MMM_COLOR_FMT_ALIGN(width * 2, alignment);
		break;
	default:
		break;
	}
invalid_input:
	return stride;
}

static inline unsigned int MMM_COLOR_FMT_UV_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	unsigned int alignment, stride = 0;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_512:
		alignment = 512;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV12:
	case MMM_COLOR_FMT_NV21:
	case MMM_COLOR_FMT_NV12_UBWC:
		alignment = 128;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV124R_UBWC:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width, alignment);
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width, 192);
		stride = MMM_COLOR_FMT_ALIGN(stride * 4/3, alignment);
		break;
	case MMM_COLOR_FMT_P010_UBWC:
	case MMM_COLOR_FMT_P010:
		alignment = 256;
		stride = MMM_COLOR_FMT_ALIGN(width * 2, alignment);
		break;
	case MMM_COLOR_FMT_P010_512:
		alignment = 512;
		stride = MMM_COLOR_FMT_ALIGN(width * 2, alignment);
		break;
	default:
		break;
	}
invalid_input:
	return stride;
}

static inline unsigned int MMM_COLOR_FMT_Y_SCANLINES(unsigned int color_fmt,
	unsigned int height)
{
	unsigned int alignment, sclines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_512:
		alignment = 512;
		break;
	case MMM_COLOR_FMT_NV12:
	case MMM_COLOR_FMT_NV21:
	case MMM_COLOR_FMT_NV12_UBWC:
	case MMM_COLOR_FMT_P010_512:
	case MMM_COLOR_FMT_P010:
		alignment = 32;
		break;
	case MMM_COLOR_FMT_NV124R_UBWC:
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
		alignment = 16;
		break;
	default:
		return 0;
	}
	sclines = MMM_COLOR_FMT_ALIGN(height, alignment);
invalid_input:
	return sclines;
}

static inline unsigned int MMM_COLOR_FMT_UV_SCANLINES(unsigned int color_fmt,
	unsigned int height)
{
	unsigned int alignment, sclines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {

	case MMM_COLOR_FMT_NV12_512:
		alignment = 256;
		break;
	case MMM_COLOR_FMT_NV12:
	case MMM_COLOR_FMT_NV21:
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
	case MMM_COLOR_FMT_P010_512:
	case MMM_COLOR_FMT_P010:
	case MMM_COLOR_FMT_NV124R_UBWC:
		alignment = 16;
		break;
	case MMM_COLOR_FMT_NV12_UBWC:
		alignment = 32;
		break;
	default:
		goto invalid_input;
	}

	sclines = MMM_COLOR_FMT_ALIGN((height+1)>>1, alignment);

invalid_input:
	return sclines;
}

static inline unsigned int MMM_COLOR_FMT_Y_META_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	int y_tile_width = 0, y_meta_stride = 0;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
		y_tile_width = 32;
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
		y_tile_width = 48;
		break;
	case MMM_COLOR_FMT_NV124R_UBWC:
		y_tile_width = 64;
		break;
	default:
		goto invalid_input;
	}

	y_meta_stride = MMM_COLOR_FMT_ROUNDUP(width, y_tile_width);
	y_meta_stride = MMM_COLOR_FMT_ALIGN(y_meta_stride, 64);

invalid_input:
	return y_meta_stride;
}

static inline unsigned int MMM_COLOR_FMT_Y_META_SCANLINES(
		unsigned int color_fmt,	unsigned int height)
{
	int y_tile_height = 0, y_meta_scanlines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_UBWC:
		y_tile_height = 8;
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
	case MMM_COLOR_FMT_NV124R_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
		y_tile_height = 4;
		break;
	default:
		goto invalid_input;
	}

	y_meta_scanlines = MMM_COLOR_FMT_ROUNDUP(height, y_tile_height);
	y_meta_scanlines = MMM_COLOR_FMT_ALIGN(y_meta_scanlines, 16);

invalid_input:
	return y_meta_scanlines;
}

static inline unsigned int MMM_COLOR_FMT_UV_META_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	int uv_tile_width = 0, uv_meta_stride = 0;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
		uv_tile_width = 16;
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
		uv_tile_width = 24;
		break;
	case MMM_COLOR_FMT_NV124R_UBWC:
		uv_tile_width = 32;
		break;
	default:
		goto invalid_input;
	}

	uv_meta_stride = MMM_COLOR_FMT_ROUNDUP((width+1)>>1, uv_tile_width);
	uv_meta_stride = MMM_COLOR_FMT_ALIGN(uv_meta_stride, 64);

invalid_input:
	return uv_meta_stride;
}

static inline unsigned int MMM_COLOR_FMT_UV_META_SCANLINES(
		unsigned int color_fmt,	unsigned int height)
{
	int uv_tile_height = 0, uv_meta_scanlines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV12_UBWC:
		uv_tile_height = 8;
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
	case MMM_COLOR_FMT_NV124R_UBWC:
	case MMM_COLOR_FMT_P010_UBWC:
		uv_tile_height = 4;
		break;
	default:
		goto invalid_input;
	}

	uv_meta_scanlines = MMM_COLOR_FMT_ROUNDUP((height+1)>>1,
							uv_tile_height);
	uv_meta_scanlines = MMM_COLOR_FMT_ALIGN(uv_meta_scanlines, 16);

invalid_input:
	return uv_meta_scanlines;
}

static inline unsigned int MMM_COLOR_FMT_RGB_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	unsigned int alignment = 0, stride = 0, bpp = 4;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_RGBA8888:
		alignment = 256;
		break;
	case MMM_COLOR_FMT_RGB565_UBWC:
		alignment = 256;
		bpp = 2;
		break;
	case MMM_COLOR_FMT_RGBA8888_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC:
	case MMM_COLOR_FMT_RGBA1010102_UBWC:
		alignment = 256;
		break;
	case MMM_COLOR_FMT_RGBA16161616F_UBWC:
		alignment = 256;
		bpp = 8;
		break;
	default:
		goto invalid_input;
	}

	stride = MMM_COLOR_FMT_ALIGN(width * bpp, alignment);

invalid_input:
	return stride;
}

static inline unsigned int MMM_COLOR_FMT_RGB_SCANLINES(unsigned int color_fmt,
	unsigned int height)
{
	unsigned int alignment = 0, scanlines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_RGBA8888:
		alignment = 32;
		break;
	case MMM_COLOR_FMT_RGBA8888_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC:
	case MMM_COLOR_FMT_RGBA1010102_UBWC:
	case MMM_COLOR_FMT_RGB565_UBWC:
	case MMM_COLOR_FMT_RGBA16161616F_UBWC:
		alignment = 16;
		break;
	case MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC:
		alignment = 16;
		height = height / 2;
		break;
	default:
		goto invalid_input;
	}

	scanlines = MMM_COLOR_FMT_ALIGN(height, alignment);

invalid_input:
	return scanlines;
}

static inline unsigned int MMM_COLOR_FMT_RGB_META_STRIDE(unsigned int color_fmt,
	unsigned int width)
{
	int rgb_tile_width = 0, rgb_meta_stride = 0;

	if (!width)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_RGBA8888_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC:
	case MMM_COLOR_FMT_RGBA1010102_UBWC:
	case MMM_COLOR_FMT_RGB565_UBWC:
		rgb_tile_width = 16;
		break;
	case MMM_COLOR_FMT_RGBA16161616F_UBWC:
		rgb_tile_width = 8;
		break;
	default:
		goto invalid_input;
	}

	rgb_meta_stride = MMM_COLOR_FMT_ROUNDUP(width, rgb_tile_width);
	rgb_meta_stride = MMM_COLOR_FMT_ALIGN(rgb_meta_stride, 64);

invalid_input:
	return rgb_meta_stride;
}

static inline unsigned int MMM_COLOR_FMT_RGB_META_SCANLINES(
		unsigned int color_fmt,	unsigned int height)
{
	int rgb_tile_height = 0, rgb_meta_scanlines = 0;

	if (!height)
		goto invalid_input;

	switch (color_fmt) {
	case MMM_COLOR_FMT_RGBA8888_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC:
	case MMM_COLOR_FMT_RGBA1010102_UBWC:
	case MMM_COLOR_FMT_RGB565_UBWC:
	case MMM_COLOR_FMT_RGBA16161616F_UBWC:
		rgb_tile_height = 4;
		break;
	default:
		goto invalid_input;
	}

	rgb_meta_scanlines = MMM_COLOR_FMT_ROUNDUP(height, rgb_tile_height);
	rgb_meta_scanlines = MMM_COLOR_FMT_ALIGN(rgb_meta_scanlines, 16);

invalid_input:
	return rgb_meta_scanlines;
}

static inline unsigned int MMM_COLOR_FMT_BUFFER_SIZE(unsigned int color_fmt,
	unsigned int width, unsigned int height)
{
	unsigned int size = 0;
	unsigned int y_plane, uv_plane, y_stride,
		uv_stride, y_sclines, uv_sclines;
	unsigned int y_ubwc_plane = 0, uv_ubwc_plane = 0;
	unsigned int y_meta_stride = 0, y_meta_scanlines = 0;
	unsigned int uv_meta_stride = 0, uv_meta_scanlines = 0;
	unsigned int y_meta_plane = 0, uv_meta_plane = 0;
	unsigned int rgb_stride = 0, rgb_scanlines = 0;
	unsigned int rgb_plane = 0, rgb_ubwc_plane = 0, rgb_meta_plane = 0;
	unsigned int rgb_meta_stride = 0, rgb_meta_scanlines = 0;

	if (!width || !height)
		goto invalid_input;

	y_stride = MMM_COLOR_FMT_Y_STRIDE(color_fmt, width);
	uv_stride = MMM_COLOR_FMT_UV_STRIDE(color_fmt, width);
	y_sclines = MMM_COLOR_FMT_Y_SCANLINES(color_fmt, height);
	uv_sclines = MMM_COLOR_FMT_UV_SCANLINES(color_fmt, height);
	rgb_stride = MMM_COLOR_FMT_RGB_STRIDE(color_fmt, width);
	rgb_scanlines = MMM_COLOR_FMT_RGB_SCANLINES(color_fmt, height);

	switch (color_fmt) {
	case MMM_COLOR_FMT_NV21:
	case MMM_COLOR_FMT_NV12:
	case MMM_COLOR_FMT_P010_512:
	case MMM_COLOR_FMT_P010:
	case MMM_COLOR_FMT_NV12_512:
		y_plane = y_stride * y_sclines;
		uv_plane = uv_stride * uv_sclines;
		size = y_plane + uv_plane;
		break;
	case MMM_COLOR_FMT_NV12_UBWC:
	case MMM_COLOR_FMT_NV124R_UBWC:
		y_meta_stride = MMM_COLOR_FMT_Y_META_STRIDE(color_fmt, width);
		uv_meta_stride = MMM_COLOR_FMT_UV_META_STRIDE(color_fmt, width);
		if (width <= INTERLACE_WIDTH_MAX &&
			height <= INTERLACE_HEIGHT_MAX &&
			(height * width) / 256 <= INTERLACE_MB_PER_FRAME_MAX) {
			y_sclines = MMM_COLOR_FMT_Y_SCANLINES(color_fmt,
								(height+1)>>1);
			y_ubwc_plane =
				MMM_COLOR_FMT_ALIGN(y_stride * y_sclines, 4096);
			uv_sclines = MMM_COLOR_FMT_UV_SCANLINES(color_fmt,
								(height+1)>>1);
			uv_ubwc_plane =	MMM_COLOR_FMT_ALIGN(
						uv_stride * uv_sclines, 4096);
			y_meta_scanlines = MMM_COLOR_FMT_Y_META_SCANLINES(
						color_fmt, (height+1)>>1);
			y_meta_plane = MMM_COLOR_FMT_ALIGN(
				y_meta_stride * y_meta_scanlines, 4096);
			uv_meta_scanlines = MMM_COLOR_FMT_UV_META_SCANLINES(
					color_fmt, (height+1)>>1);
			uv_meta_plane = MMM_COLOR_FMT_ALIGN(uv_meta_stride *
				uv_meta_scanlines, 4096);
			size = (y_ubwc_plane + uv_ubwc_plane + y_meta_plane +
				uv_meta_plane)*2;
		} else {
			y_sclines = MMM_COLOR_FMT_Y_SCANLINES(color_fmt,
									height);
			y_ubwc_plane =
				MMM_COLOR_FMT_ALIGN(y_stride * y_sclines, 4096);
			uv_sclines = MMM_COLOR_FMT_UV_SCANLINES(color_fmt,
									height);
			uv_ubwc_plane =
				MMM_COLOR_FMT_ALIGN(uv_stride * uv_sclines,
									4096);
			y_meta_scanlines = MMM_COLOR_FMT_Y_META_SCANLINES(
							color_fmt, height);
			y_meta_plane = MMM_COLOR_FMT_ALIGN(
				y_meta_stride * y_meta_scanlines, 4096);
			uv_meta_scanlines = MMM_COLOR_FMT_UV_META_SCANLINES(
							color_fmt, height);
			uv_meta_plane = MMM_COLOR_FMT_ALIGN(uv_meta_stride *
				uv_meta_scanlines, 4096);
			size = (y_ubwc_plane + uv_ubwc_plane + y_meta_plane +
				uv_meta_plane);
		}
		break;
	case MMM_COLOR_FMT_NV12_BPP10_UBWC:
		y_ubwc_plane = MMM_COLOR_FMT_ALIGN(y_stride * y_sclines, 4096);
		uv_ubwc_plane = MMM_COLOR_FMT_ALIGN(uv_stride * uv_sclines,
									4096);
		y_meta_stride = MMM_COLOR_FMT_Y_META_STRIDE(color_fmt, width);
		y_meta_scanlines = MMM_COLOR_FMT_Y_META_SCANLINES(color_fmt,
									height);
		y_meta_plane = MMM_COLOR_FMT_ALIGN(
				y_meta_stride * y_meta_scanlines, 4096);
		uv_meta_stride = MMM_COLOR_FMT_UV_META_STRIDE(color_fmt, width);
		uv_meta_scanlines = MMM_COLOR_FMT_UV_META_SCANLINES(color_fmt,
									height);
		uv_meta_plane = MMM_COLOR_FMT_ALIGN(uv_meta_stride *
					uv_meta_scanlines, 4096);

		size = y_ubwc_plane + uv_ubwc_plane + y_meta_plane +
			uv_meta_plane;
		break;
	case MMM_COLOR_FMT_P010_UBWC:
		y_ubwc_plane = MMM_COLOR_FMT_ALIGN(y_stride * y_sclines, 4096);
		uv_ubwc_plane = MMM_COLOR_FMT_ALIGN(uv_stride * uv_sclines,
									4096);
		y_meta_stride = MMM_COLOR_FMT_Y_META_STRIDE(color_fmt, width);
		y_meta_scanlines = MMM_COLOR_FMT_Y_META_SCANLINES(color_fmt,
									height);
		y_meta_plane = MMM_COLOR_FMT_ALIGN(
				y_meta_stride * y_meta_scanlines, 4096);
		uv_meta_stride = MMM_COLOR_FMT_UV_META_STRIDE(color_fmt, width);
		uv_meta_scanlines = MMM_COLOR_FMT_UV_META_SCANLINES(color_fmt,
									height);
		uv_meta_plane = MMM_COLOR_FMT_ALIGN(uv_meta_stride *
					uv_meta_scanlines, 4096);

		size = y_ubwc_plane + uv_ubwc_plane + y_meta_plane +
			uv_meta_plane;
		break;
	case MMM_COLOR_FMT_RGBA8888:
		rgb_plane = MMM_COLOR_FMT_ALIGN(rgb_stride  * rgb_scanlines,
									4096);
		size = rgb_plane;
		break;
	case MMM_COLOR_FMT_RGBA8888_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_8_5_UBWC:
	case MMM_COLOR_FMT_RGBA8888_L_2_1_UBWC:
	case MMM_COLOR_FMT_RGBA1010102_UBWC:
	case MMM_COLOR_FMT_RGB565_UBWC:
	case MMM_COLOR_FMT_RGBA16161616F_UBWC:
		rgb_ubwc_plane = MMM_COLOR_FMT_ALIGN(rgb_stride * rgb_scanlines,
							4096);
		rgb_meta_stride = MMM_COLOR_FMT_RGB_META_STRIDE(color_fmt,
									width);
		rgb_meta_scanlines = MMM_COLOR_FMT_RGB_META_SCANLINES(color_fmt,
					height);
		rgb_meta_plane = MMM_COLOR_FMT_ALIGN(rgb_meta_stride *
					rgb_meta_scanlines, 4096);
		size = rgb_ubwc_plane + rgb_meta_plane;
		break;
	default:
		break;
	}
invalid_input:
	return MMM_COLOR_FMT_ALIGN(size, 4096);
}

static inline unsigned int MMM_COLOR_FMT_BUFFER_SIZE_USED(
		unsigned int color_fmt,	unsigned int width,
		unsigned int height, unsigned int interlace)
{
	unsigned int size = 0;
	unsigned int y_stride, uv_stride, y_sclines, uv_sclines;
	unsigned int y_ubwc_plane = 0, uv_ubwc_plane = 0;
	unsigned int y_meta_stride = 0, y_meta_scanlines = 0;
	unsigned int uv_meta_stride = 0, uv_meta_scanlines = 0;
	unsigned int y_meta_plane = 0, uv_meta_plane = 0;

	if (!width || !height)
		goto invalid_input;

	if (!interlace && (color_fmt == MMM_COLOR_FMT_NV12_UBWC ||
	    color_fmt == MMM_COLOR_FMT_NV124R_UBWC)) {
		y_stride = MMM_COLOR_FMT_Y_STRIDE(color_fmt, width);
		uv_stride = MMM_COLOR_FMT_UV_STRIDE(color_fmt, width);
		y_sclines = MMM_COLOR_FMT_Y_SCANLINES(color_fmt, height);
		y_ubwc_plane = MMM_COLOR_FMT_ALIGN(y_stride * y_sclines, 4096);
		uv_sclines = MMM_COLOR_FMT_UV_SCANLINES(color_fmt, height);
		uv_ubwc_plane = MMM_COLOR_FMT_ALIGN(uv_stride * uv_sclines,
									4096);
		y_meta_stride = MMM_COLOR_FMT_Y_META_STRIDE(color_fmt, width);
		y_meta_scanlines =
			MMM_COLOR_FMT_Y_META_SCANLINES(color_fmt, height);
		y_meta_plane = MMM_COLOR_FMT_ALIGN(
			y_meta_stride * y_meta_scanlines, 4096);
		uv_meta_stride = MMM_COLOR_FMT_UV_META_STRIDE(color_fmt, width);
		uv_meta_scanlines =
			MMM_COLOR_FMT_UV_META_SCANLINES(color_fmt, height);
		uv_meta_plane = MMM_COLOR_FMT_ALIGN(uv_meta_stride *
			uv_meta_scanlines, 4096);
		size = (y_ubwc_plane + uv_ubwc_plane + y_meta_plane +
			uv_meta_plane);
		size = MMM_COLOR_FMT_ALIGN(size, 4096);
	} else {
		size = MMM_COLOR_FMT_BUFFER_SIZE(color_fmt, width, height);
	}
invalid_input:
	return size;
}

#endif
