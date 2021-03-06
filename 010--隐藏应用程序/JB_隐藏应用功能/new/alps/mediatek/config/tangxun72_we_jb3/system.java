#
# system.prop for generic sdk
#

rild.libpath=/system/lib/mtk-ril.so
rild.libargs=-d /dev/ttyC0


# MTK, Infinity, 20090720 {
wifi.interface=wlan0
# MTK, Infinity, 20090720 }

# MTK, mtk03034, 20101210 {
ro.mediatek.wlan.wsc=1
# MTK, mtk03034 20101210}
# MTK, mtk03034, 20110318 {
ro.mediatek.wlan.p2p=1
# MTK, mtk03034 20110318}

# MTK, mtk03034, 20101213 {
mediatek.wlan.ctia=0
# MTK, mtk03034 20101213}


#
wifi.tethering.interface=ap0
#

ro.opengles.version=131072

wifi.direct.interface=p2p0
dalvik.vm.heapgrowthlimit=96m
dalvik.vm.heapsize=128m

# USB MTP WHQL
ro.sys.usb.mtp.whql.enable=0

# Power off opt in IPO
sys.ipo.pwrdncap=2

ro.sys.usb.storage.type=mtp,mass_storage

# USB BICR function
ro.sys.usb.bicr=yes

# USB Charge only function
ro.sys.usb.charging.only=yes

# audio
ro.camera.sound.forced=0
ro.audio.silent=0

ro.zygote.preload.enable=0

ro.kernel.zio=38,108,105,16

persist.sys.timezone=Asia/Shanghai

ro.sf.lcd_density=160

ro.usb.storage.name=M706C

#internal storage
ro.phone.size = 0

#tablet storage
ro.sdcard.size = 0

#ram size
ro.ram.size = 0


ro.flashlight.state=1

#0(4:3) 1(fullscreen)
ro.fullscreen.state=0

# 2(30M 640x480) 5(200M 1600x1200) 7(500M 2560x1920) 8(800M 3264x2448)
ro.camera.pictureSize.front=2
ro.camera.pictureSize.back=5

ro.def.screen.brightness=204

#com.android.contacts/com.android.dialer/com.android.mms/com.mediatek.StkSelection
ro.sys.hide=  