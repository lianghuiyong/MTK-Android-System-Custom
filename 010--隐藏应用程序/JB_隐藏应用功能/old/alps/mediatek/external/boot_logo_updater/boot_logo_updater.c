/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*****************************************************************************
*  Copyright Statement:
*  --------------------
*  This software is protected by Copyright and the information contained
*  herein is confidential. The software may not be copied and the information
*  contained herein may not be used or disclosed except with the written
*  permission of MediaTek Inc. (C) 2008
*
*  BY OPENING THIS FILE, BUYER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
*  THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
*  RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO BUYER ON
*  AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
*  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
*  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
*  NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
*  SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
*  SUPPLIED WITH THE MEDIATEK SOFTWARE, AND BUYER AGREES TO LOOK ONLY TO SUCH
*  THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. MEDIATEK SHALL ALSO
*  NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE RELEASES MADE TO BUYER'S
*  SPECIFICATION OR TO CONFORM TO A PARTICULAR STANDARD OR OPEN FORUM.
*
*  BUYER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND CUMULATIVE
*  LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
*  AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
*  OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY BUYER TO
*  MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
*
*  THE TRANSACTION CONTEMPLATED HEREUNDER SHALL BE CONSTRUED IN ACCORDANCE
*  WITH THE LAWS OF THE STATE OF CALIFORNIA, USA, EXCLUDING ITS CONFLICT OF
*  LAWS PRINCIPLES.  ANY DISPUTES, CONTROVERSIES OR CLAIMS ARISING THEREOF AND
*  RELATED THERETO SHALL BE SETTLED BY ARBITRATION IN SAN FRANCISCO, CA, UNDER
*  THE RULES OF THE INTERNATIONAL CHAMBER OF COMMERCE (ICC).
*
*****************************************************************************/

#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <stdlib.h>
#include <ctype.h>

#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <cutils/properties.h>
#include <utils/Log.h>

#include "libnvram.h"
#include "CFG_file_lid.h"
#include "Custom_NvRam_LID.h"
#include "CFG_PRODUCT_INFO_File.h"

#define MAX_RETRY_COUNT 20

char const * const fb_dev_node_paths[] = {
        "/dev/graphics/fb%u",
        "/dev/fb%u",
        0
};
/* no use for kernel logo display
extern void bootlogo_show_boot();
extern void bootlogo_fb_init();
extern void bootlogo_fb_deinit();
*/
const char LOGO_PATH[] = "/system/media/images/boot_logo";
const char LOGO_PATH1[] = "/system/media/images/boot_logo_sub";

#define RGB565_TO_ARGB8888(x)   \
    ((((x) &   0x1F) << 3) |    \
     (((x) &  0x7E0) << 5) |    \
     (((x) & 0xF800) << 8) |    \
     (0xFF << 24)) // opaque

#ifdef LOG_TAG
#undef LOG_TAG
#define LOG_TAG "BootLogoUpdater"
#endif


#define BOOT_MODE_PATH "/sys/class/BOOT/BOOT/boot/boot_mode"
#define LCD_BACKLIGHT_PATH "/sys/class/leds/lcd-backlight/brightness"
#define BOOT_REASON_SYS_PROPERTY "sys.boot.reason"
#define BOOT_PACKAGE_SYS_PROPERTY "persist.sys.bootpackage"

#define ALIGN_TO(x, n)  \
    (((x) + ((n) - 1)) & ~((n) - 1))
int getFlag(unsigned char *result)
{
    int read_nvram_ready_retry = 0;
    F_ID fid;
    int rec_size = 0;
    int rec_num = 0;
    PRODUCT_INFO *product_struct;
    bool isread = true;
    char nvram_init_val[128] = {0};
	
    while(read_nvram_ready_retry < MAX_RETRY_COUNT)
    {
        read_nvram_ready_retry++;
        property_get("nvram_init", nvram_init_val, NULL);
        if(strcmp(nvram_init_val, "Ready") == 0)
        {
            break;
        }
        else
        {
            usleep(500*1000);
        }
    }

    if(read_nvram_ready_retry >= MAX_RETRY_COUNT)
    {
        return false;
    }

    product_struct= (PRODUCT_INFO *)malloc(sizeof(PRODUCT_INFO));
	
    if(product_struct == NULL)
    {
        return false;
    }

    fid = NVM_GetFileDesc(AP_CFG_REEB_PRODUCT_INFO_LID, &rec_size, &rec_num, isread);

    if(fid.iFileDesc < 0)
    {
        return false;
    }

    if(rec_size != read(fid.iFileDesc, product_struct, rec_size*rec_num))
    {
        free(product_struct);
        return false;
    }

    memcpy(result,&product_struct->logo_flag,1);
    free(product_struct);
	
    if(!NVM_CloseFileDesc(fid))
    {
        return false;
    }

    return true;
}
    
/*
 * return value:
 * 0: normal
 * 1: Alarm boot
 * 2: Schedule power-on
 */
int updateBootReason() {
    int fd;
    size_t s;
    char boot_mode;
    char boot_reason; // 0: normal boot, 1: alarm boot
    char propVal[PROPERTY_VALUE_MAX];
    int ret = 0;

    fd = open(BOOT_MODE_PATH, O_RDWR);
    if (fd < 0) {
        printf("[boot_logo_updater]fail to open: %s\n", BOOT_MODE_PATH);
        boot_reason = '0';
    } else {
        s = read(fd, (void *)&boot_mode, sizeof(boot_mode));
        close(fd);
    
        if(s <= 0) {
           printf("[boot_logo_updater]can't read the boot_mode");
            boot_reason = '0';
        } else {
            // ALARM_BOOT = 7 
            printf("[boot_logo_updater]boot_mode = %c", boot_mode);
            if ( boot_mode == '7' ) {
        //add for encrypt mode to avoid invoke the power-off alarm
        property_get("vold.decrypt", propVal, "");
        if (!strcmp(propVal, "") || !strcmp(propVal, "trigger_restart_framework")) {
            boot_reason = '1';
                    ret = 1;
                } else {
            boot_reason = '0';
            ret = 2;
                }
            } else {
                // schedule on/off, normal boot
                property_get(BOOT_PACKAGE_SYS_PROPERTY, propVal, "0");
                int package = atoi(propVal);
                printf("[boot_logo_updater]boot package = %d", package);
                if ( package != 1 ) {
                    // it's not triggered by Desk Clock, change to normal boot
                    ret = 2;
                }
                boot_reason = '0';
            }
        }
    }
    sprintf(propVal, "%c", boot_reason);
    printf("[boot_logo_updater]update boot reason = %s, ret = %d", propVal, ret);
    property_set(BOOT_REASON_SYS_PROPERTY, propVal);
    return ret;
}
// no use
/*
void showBootLogo() {
    bootlogo_fb_init();
    bootlogo_show_boot();
    bootlogo_fb_deinit();
    sleep(1);
}
*/

int write_to_file(const char* path, const char* buf, int size) {

    if (!path) {
        printf("[boot_logo_updater]path is null!");
        return 0;
    }

    int fd = open(path, O_RDWR);
    if (fd == -1) {
        printf("[boot_logo_updater]Could not open '%s'\n", path);
        return 0;
    }

    int count = write(fd, buf, size); 
    if (count != size) {
        printf("[boot_logo_updater]write file (%s) fail, count: %d\n", path, count);
        return 0;
    }
    close(fd);
    return count;
}

void set_int_value(const char * path, const int value)
{
    char buf[32];
    sprintf(buf, "%d", value);

    write_to_file(path, buf, strlen(buf));
}


int main(void)
{
    int fb = -1;
    int fd = -1;
    size_t fbsize = 0;
    ssize_t rdsize = 0;
    unsigned int x_virtual = 0;
    char name[64];
	char logobuff[15];
    struct fb_var_screeninfo vinfo;
    struct fb_fix_screeninfo finfo;
    void *fbbuf = NULL;

    size_t rgb565_logo_size = 0;
    unsigned short *rgb565_logo = NULL;

    unsigned int i = 0;

    printf("[boot_logo_updater]starting boot_logo_updater ...");
    
    int ret = updateBootReason();
    if (ret == 1) {
        printf("[boot_logo_updater]skip the boot logo!");
        set_int_value(LCD_BACKLIGHT_PATH, 120);
        return 0;    
    } else if (ret == 2) {
        printf("[boot_logo_updater]schedule on");     
    }

    // (1) open framebuffer driver
    
    while ((fb == -1) && fb_dev_node_paths[i]) {
        snprintf(name, 64, fb_dev_node_paths[i], 0);
        fb = open(name, O_RDWR, 0);
        i++;
    }

    if (fb < 0)
        return -1;

    // (2) get screen info

    if (ioctl(fb, FBIOGET_VSCREENINFO, &vinfo) < 0) {
        fprintf(stderr, "ioctl FBIOGET_VSCREENINFO failed\n");
        goto done;
    }

    if (ioctl(fb, FBIOGET_FSCREENINFO, &finfo) < 0) {
        fprintf(stderr, "ioctl FBIOGET_FSCREENINFO failed\n");
        goto done;
    }

    x_virtual = finfo.line_length / (vinfo.bits_per_pixel / 8);
    fbsize = x_virtual * vinfo.yres * (vinfo.bits_per_pixel / 8);
//    fbsize = vinfo.xres_virtual * vinfo.yres * (vinfo.bits_per_pixel / 8);
//    fbsize = finfo.line_length * vinfo.yres;

    rgb565_logo_size = vinfo.xres * vinfo.yres * 2;

    printf("[boot_logo_updater] vinfo.bits_per_pixel = %d\n",vinfo.bits_per_pixel);
    printf("[boot_logo_updater] vinfo.xres= %d, vinfo.yres= %d \n",vinfo.xres,vinfo.yres);
    printf("[boot_logo_updater] finfo.line_length = %d\n",finfo.line_length);
    printf("[boot_logo_updater] vinfo.xres_virtual = %d\n",vinfo.xres_virtual);

    printf("[boot_logo_updater] x_virtual = %d\n",x_virtual);
    printf("[boot_logo_updater] fbsize= %d\n",fbsize);
    printf("[boot_logo_updater] rgb565_logo_size = %d\n",rgb565_logo_size);
    // (3) open logo file
    unsigned char flag;
	getFlag(&flag);
	property_get("persist.sys.bootlogo", logobuff, "unknown");

	
    if(!flag)
	{
		if(strcmp(logobuff, "unknown") == 0){
			property_set("persist.sys.bootlogo","default");
		}
    	if ((fd = open(LOGO_PATH, O_RDONLY)) < 0) {
        	fprintf(stderr, "failed to open logo file: %s\n", LOGO_PATH);
        	goto done;
    	}
	}
    else 
    {
		if(strcmp(logobuff, "unknown") == 0){
			property_set("persist.sys.bootlogo","customer");
		}
    	if ((fd = open(LOGO_PATH1, O_RDONLY)) < 0) {
    		fprintf(stderr, "failed to open logo file: %s\n", LOGO_PATH1);
    		goto done;
    	}
    }		

    // (4) map framebuffer

    fbbuf = mmap(0, fbsize*2, PROT_READ|PROT_WRITE, MAP_SHARED, fb, 0);
    if (fbbuf == (void *)-1) {
        fprintf(stderr, "failed to map framebuffer\n");
        fbbuf = NULL;
        goto done;
    }

    if(vinfo.yoffset == 0)
    {
        fbbuf = (void *)((unsigned int)fbbuf + fbsize);
        vinfo.yoffset = vinfo.yres;
    }
    else
    {
        vinfo.yoffset = 0;
    }

    // (5) copy the 2nd logo to frmaebuffer

    rgb565_logo = malloc(rgb565_logo_size);
    if (!rgb565_logo) {
        fprintf(stderr, "allocate %d bytes memory for boot logo failed\n",
                rgb565_logo_size);
        goto done;
    }

    rdsize = read(fd, rgb565_logo, rgb565_logo_size);
    if (rdsize < (ssize_t)rgb565_logo_size) {
        fprintf(stderr, "logo file size: %ld bytes, "
                        "while expected size: %d bytes\n",
                        rdsize, rgb565_logo_size);
        goto done;
    }

    if (16 == vinfo.bits_per_pixel) // RGB565
    {
//        memcpy(fbbuf, rgb565_logo, rgb565_logo_size);
        unsigned short *s = rgb565_logo;
        unsigned short *d = fbbuf;
        unsigned j,k;
/*
        for (j = 0; j < vinfo.yres; ++ j)
        {
            memcpy((void*)d, (void*)s, vinfo.xres * 2);
            d += finfo.line_length;
            s += vinfo.xres * 2;
        }
        */
        for (j = 0; j < vinfo.yres; ++ j){
            for(k = 0; k < vinfo.xres; ++ k)
            {
                *d++ = *s++;
            }
            for(k = vinfo.xres; k < x_virtual; ++ k){
                *d++ = 0xFFFF;
            }
        }
    }
    else if (32 == vinfo.bits_per_pixel) // ARGB8888
    {
        unsigned short src_rgb565 = 0;

        unsigned short *s = rgb565_logo;
        unsigned int   *d = fbbuf;
        int j = 0;
        int k = 0;
/*
        for (j = 0; j < vinfo.xres * vinfo.yres; ++ j)
        {
            src_rgb565 = *s++;
            *d++ = RGB565_TO_ARGB8888(src_rgb565);
        }
*/  
    
#if 1
        if(0 == strncmp(MTK_LCM_PHYSICAL_ROTATION, "270", 3))
        {
            printf("[boot_logo_updater]270");
            unsigned int l;
            for (j=0; j<vinfo.xres; j++){
                for (k=0, l=vinfo.yres-1; k<vinfo.yres; k++, l--)
                {
                    src_rgb565 = *s++;
                    d = fbbuf + ((x_virtual * l + j) << 2);
                    *d = RGB565_TO_ARGB8888(src_rgb565);
                }
            }
        }
        else if(0 == strncmp(MTK_LCM_PHYSICAL_ROTATION, "90", 2))
        {
            printf("[boot_logo_updater]90\n");
            unsigned int l;
            for (j=vinfo.xres - 1; j>=0; j--){
                for (k=0, l=0; k<vinfo.yres; k++, l++)
                {
                    src_rgb565 = *s++;
                    d = fbbuf + ((x_virtual * l + j) << 2);
                    *d = RGB565_TO_ARGB8888(src_rgb565);
                }
            }
        }
        else if(0 == strncmp(MTK_LCM_PHYSICAL_ROTATION, "180", 3))
        {
            printf("[boot_logo_updater]180\n");            
            unsigned int height = vinfo.yres;
            unsigned int width = vinfo.xres;
            unsigned short *src = (unsigned short*)s + ((height - 1) * width);
            unsigned int *dst = d;
            //UINT16 *pLine2 = (UINT16*)addr;
            for (j = 0; j < height; ++ j) {
                for (k = 0; k < width; ++ k) {
                    src_rgb565 = *(src+width-k-1);
                    *(dst+k) = RGB565_TO_ARGB8888(src_rgb565);
                }
                for (k = width; k < x_virtual; ++ k) {
                    *(dst+k) = 0xFFFFFFFF;
                }
                dst += ALIGN_TO(width,32);
                src -= width;
            }
        }
        else
#endif      
        {
            printf("[boot_logo_updater]normal\n");
            for (j = 0; j < vinfo.yres; ++ j){
                for(k = 0; k < vinfo.xres; ++ k)
                {
                    src_rgb565 = *s++;
                    *d++ = RGB565_TO_ARGB8888(src_rgb565);
                }
                for(k = vinfo.xres; k < x_virtual; ++ k){                   
                    *d++ = 0xFFFFFFFF;
                }
            }
            printf("[boot_logo_updater] loop copy color over\n");
        }
    }
    else
    {
        fprintf(stderr, "unknown format bpp: %d\n", vinfo.bits_per_pixel);
        goto done;
    }

    // (6) flip to front buffer immediately

    //vinfo.yoffset = 0;
    vinfo.activate |= (FB_ACTIVATE_FORCE | FB_ACTIVATE_NOW);
    
    if (ioctl(fb, FBIOPUT_VSCREENINFO, &vinfo) < 0) {
        fprintf(stderr, "ioctl FBIOPUT_VSCREENINFO flip failed\n");
        goto done;
    }

    printf("[boot_logo_updater] update boot logo successfully!\n");

done:    
    if (rgb565_logo) free(rgb565_logo);
    if (fbbuf) munmap(fbbuf, fbsize);
    if (fd >= 0) close(fd);
    if (fb >= 0) close(fb);

    return 0;
}
