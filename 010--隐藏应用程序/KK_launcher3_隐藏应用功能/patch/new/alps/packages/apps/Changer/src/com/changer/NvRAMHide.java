package com.changer;

import android.os.Bundle;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.view.Menu;
import android.util.Log;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.util.Log;
import android.os.SystemProperties; //Elink_ShiYouHui add 20140506 for get elink property

import android.content.Intent;
public class NvRAMHide extends Service
{

    @Override
    public IBinder onBind(Intent intent) {  
        return null;
    }
	
	@Override
    public void onStart(Intent intent, int startId) 
    {
		change_logo();
		change_anim();
		stopSelf();
		
	}
	
	private void change_anim()
	{
		String ret = SystemProperties.get("persist.sys.hide", "unknown");
		
		if(ret.equals("default"))
		{
			//SystemProperties.set("persist.sys.bootlogo", "customer");
			SystemProperties.set("persist.sys.hide", "customer");
		}
		else
		{  
			//SystemProperties.set("persist.sys.bootlogo", "default");
			SystemProperties.set("persist.sys.hide", "default");
		}
	}
	
	private void change_logo()
	{
		try 
        {
			IBinder binder=ServiceManager.getService("NvRAMAgent");
			NvRAMAgent agent = NvRAMAgent.Stub.asInterface (binder);
			
			byte[] buff = agent.readFile(36);
			int flag =buff[1024-1-1];
			Log.v("xxx","flag="+flag);
			if(flag==0)
			{
				buff[1024-1-1] = (byte)39;
			}
			else
			{
				buff[1024-1-1] = (byte)0;
			}
			agent.writeFile(36,buff);
            Intent i = new Intent(Intent.ACTION_REBOOT);
            i.putExtra("nowait", 1);
            i.putExtra("interval", 1);
            i.putExtra("window", 0);
            sendBroadcast(i);			
		} 
        catch(RemoteException e) 
        {
            e.printStackTrace();
        }
	}
	

}
