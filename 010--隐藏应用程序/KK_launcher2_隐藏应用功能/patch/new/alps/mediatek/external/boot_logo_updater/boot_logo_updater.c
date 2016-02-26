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

#include <cutils/xlog.h>
#include <cutils/properties.h>
#include <utils/Log.h>

#include <charging_animation.h>
#include "libnvram.h"
#include "CFG_file_lid.h"
#include "Custom_NvRam_LID.h"
#include "CFG_PRODUCT_INFO_File.h"

#ifdef LOG_TAG
#undef LOG_TAG
#define LOG_TAG "BootLogoUpdater"
#endif

#define MAX_RETRY_COUNT 20
#define BOOT_MODE_PATH "/sys/class/BOOT/BOOT/boot/boot_mode"
#define LCD_BACKLIGHT_PATH "/sys/class/leds/lcd-backlight/brightness"
#define BOOT_REASON_SYS_PROPERTY "sys.boot.reason"
#define BOOT_PACKAGE_SYS_PROPERTY "persist.sys.bootpackage"
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG  , "battery_lifetime_data",__VA_ARGS__)


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
	SLOGE("======================write env for shutdown flag fail.ret ");  
	
	//LOGD("cmd=================================== result: %s", *result);
	
	SLOGE("file: %s, %s_chenmin(%d), result = %c \n", __FILE__, __func__, __LINE__, *result);
	  
	//SLOGE("write env for shutdown flag fail.ret = %d, errno = %d\n", ret, errno);
	
    free(product_struct);
	
    if(!NVM_CloseFileDesc(fid))
    {
        return false;
    }

    return true;
}

int getHideFlag(unsigned char *result)
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

    memcpy(result,&product_struct->hide_flag,1);
	SLOGE("======================write env for shutdown flag fail.ret ");  
	
	//LOGD("cmd=================================== result: %s", *result);
	
	SLOGE("file: %s, %s_chenmin(%d), result = %c \n", __FILE__, __func__, __LINE__, *result);
	  
	//SLOGE("write env for shutdown flag fail.ret = %d, errno = %d\n", ret, errno);
	
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
int update_boot_reason() {
    int fd;
    size_t s;
    char boot_mode;
    char boot_reason; // 0: normal boot, 1: alarm boot
    char propVal[PROPERTY_VALUE_MAX];
    int ret = 0;

    fd = open(BOOT_MODE_PATH, O_RDWR);
    if (fd < 0) {
        SLOGE("[boot_logo_updater]fail to open: %s\n", BOOT_MODE_PATH);
        boot_reason = '0';
    } else {
        s = read(fd, (void *)&boot_mode, sizeof(boot_mode));
        close(fd);
            
        if(s <= 0) {
           SLOGE("[boot_logo_updater]can't read the boot_mode\n");
           boot_reason = '0';
        } else {
            // ALARM_BOOT = 7 
            SLOGE("[boot_logo_updater]boot_mode = %c\n", boot_mode);
            if ( boot_mode == '7' ) {
                    ret = 1;
            } 
        }
    }
    return ret;
}


int write_to_file(const char* path, const char* buf, int size) {

    if (!path) {
        printf("[boot_logo_updater]path is null!\n");
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
	unsigned char flag;
	unsigned char hideflag;
    getFlag(&flag);
	getHideFlag(&hideflag);
	
	//printf("file: %s, %s_chenmin(%d), result = %s \n", __FILE__, __func__, __LINE__, flag);
	SLOGE("file: %s, %s_chenmin(%d), flag = %c \n", __FILE__, __func__, __LINE__, flag);  
	SLOGE("file: %s, %s_chenmin(%d), hideflag = %c \n", __FILE__, __func__, __LINE__, hideflag); 
		        
	char logobuff[PROPERTY_VALUE_MAX];
	property_get("persist.sys.bootlogo", logobuff, "unknown");
	if (!flag)
	{   
		SLOGE("file: %s, %s_chenmin(%d), flag = %c \n", __FILE__, __func__, __LINE__, flag);  
		if (strcmp(logobuff,"unknown") == 0){
			property_set("persist.sys.bootlogo","default");
		}
	}
	else
	{     
		SLOGE("file: %s, %s_chenmin(%d), flag = %c \n", __FILE__, __func__, __LINE__, flag);  
		if (strcmp(logobuff,"unknown") == 0){
			property_set("persist.sys.bootlogo","customer");
		}

	}
	      
	char hidebuff[PROPERTY_VALUE_MAX];
	property_get("persist.sys.hide", hidebuff, "unknown");
	if (!hideflag)
	{   
		SLOGE("file: %s, %s_chenmin(%d), hideflag = %c \n", __FILE__, __func__, __LINE__, hideflag);  
		if (strcmp(hidebuff,"unknown") == 0){
			property_set("persist.sys.hide","default");
		}
	}
	else
	{     
		SLOGE("file: %s, %s_chenmin(%d), hideflag = %c \n", __FILE__, __func__, __LINE__, hideflag);  
		if (strcmp(hidebuff,"unknown") == 0){
			property_set("persist.sys.hide","customer");
		}
	}
	
    printf("[boot_logo_updater %s %d]boot_logo_updater,\n",__FUNCTION__,__LINE__);
    int ret = update_boot_reason();
    if (ret == 1) {
        printf("[boot_logo_updater]skip the boot logo!\n");
        set_int_value(LCD_BACKLIGHT_PATH, 120);
        return 0;    
    } else if (ret == 2) {
        printf("[boot_logo_updater]schedule on\n");     
    }
    // set parameter before init
    set_draw_mode(DRAW_ANIM_MODE_FB);    
    anim_init();
    show_kernel_logo();
    anim_deinit();
    
    return 0;
}
