package com.android.inputmethod.latin;
  
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.text.TextUtils;
import android.content.SharedPreferences.Editor; 
import android.preference.PreferenceManager; 

  
public class LatinImeReceiver extends BroadcastReceiver {
    private static final String TAG = LatinImeReceiver.class.getSimpleName();
    
    private static final String[] DEFAULT_LATIN_IME_LANGUAGES = {"en_US","vi"};//默认开启输入法的语言
    
    @Override
    public void onReceive(Context context, Intent intent) {
    // Set the default input language at the system boot completed.
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG,"onReceive:ACTION_BOOT_COMPLETED");
            SharedPreferences sp = context.getSharedPreferences("default_input_language_config", Context.MODE_PRIVATE);
            boolean hasSet = sp.getBoolean("has_set", false);
         
            if (!hasSet) {
                setDefaultSubtypes(context);
                sp.edit().putBoolean("has_set", true).commit();
            }
        }
    }
     
    /**
    * M: Set the default IME subtype.
    */
      
    private void setDefaultSubtypes(Context context) {  
        Log.i(TAG,"setDefaultSubtypes");
        final String serviceName = "com.android.inputmethod.latin/.LatinIME";
        final String currentPackageName = "com.android.inputmethod.latin";
        final String enable = Settings.Secure.getString(context.getContentResolver(), 
                                                    Settings.Secure.ENABLED_INPUT_METHODS);
        Log.i(TAG,"enable="+enable);//com.android.inputmethod.latin/.LatinIME
        
        final InputMethodManager imm = (InputMethodManager) context.getSystemService( Context.INPUT_METHOD_SERVICE);
        final StringBuilder builder = new StringBuilder();
             
        // Get sub type hash code
        for (InputMethodInfo info : imm.getInputMethodList()) {
            if (currentPackageName.equals(info.getPackageName())) {
                Log.i(TAG,"info.getSubtypeCount()="+info.getSubtypeCount());//55
                for (int i = 0; i < info.getSubtypeCount(); i++) {  
                    final InputMethodSubtype subtype = info.getSubtypeAt(i); 
                    final String locale = subtype.getLocale().toString();
                    Log.i(TAG,"locale="+locale);
                    if (isDefaultLocale(locale)) {
                        Log.i(TAG, "default enabled subtype locale = " + locale);
                        builder.append(';');
                        builder.append(subtype.hashCode());
                    }
                }
                break;
            }
        }
      
        // Insert the sub type 
        if (builder.length() > 0 && !TextUtils.isEmpty(enable)) {
            final String subtype = builder.toString();     
            builder.setLength(0);      
            final int index = enable.indexOf(serviceName) + serviceName.length();    
            
            if (enable.length() > index) {      
                builder.append(enable.substring(0, index));     
                builder.append(subtype);      
                builder.append(enable.substring(index));     
            } else if (enable.length() == index) {     
                builder.append(enable);     
                builder.append(subtype);     
            } else {     
                return;     
            }
        } 
        else {  
            Log.w(TAG, "Build Latin IME subtype failed: " + " builder length = " + builder.length()     + 
                    "; enable isEmpty :" + TextUtils.isEmpty(enable));     
            return;     
        }
          
        
        /*android/packages/inputmethods/LatinIME/java/res/xml/method.xml
            -921088104;529847764分别代表en_US和th
        */
        Log.i(TAG,"commoit:"+builder.toString());//com.android.inputmethod.latin/.LatinIME;-921088104;529847764
        
        // Commit the result  
        android.provider.Settings.Secure.putString(context.getContentResolver(),  
                                android.provider.Settings.Secure.ENABLED_INPUT_METHODS, builder.toString()); 
        String lastInputMethodId = Settings.Secure.getString(context.getContentResolver(), 
                                                    Settings.Secure.DEFAULT_INPUT_METHOD); 
        Log.w(TAG, "DEFAULT_INPUT_METHOD = " + lastInputMethodId); //com.android.inputmethod.latin/.LatinIME
        
        if(lastInputMethodId.equals(serviceName)) {  
            Log.w(TAG, "DEFAULT_INPUT_METHOD = com.android.inputmethod.latin/.LatinIME" );
            
            for (InputMethodInfo info : imm.getInputMethodList()) {
                if (currentPackageName.equals(info.getPackageName())) {
                    for (int i = 0; i < info.getSubtypeCount(); i++) { 
                        final InputMethodSubtype subtype = info.getSubtypeAt(i);
                        final String[] locales = DEFAULT_LATIN_IME_LANGUAGES;
                        Log.w(TAG, "i = " + i + ", locales[0] = " + locales[0]);
                        if((subtype.getLocale()).equals(locales[0])) {
                            Log.w(TAG, "putString " + subtype.hashCode());
                            android.provider.Settings.Secure.putInt(context.getContentResolver(),  
                                android.provider.Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, subtype.hashCode());
                        } 
                    }  
                }
            }
        }
    }
         
    /**  
    * M: Check if the current locale is default or not. 
    */  
    private boolean isDefaultLocale (String locale) { 
        final String[] locales = DEFAULT_LATIN_IME_LANGUAGES;
         
        for (String s : locales) {
            if (s.equals(locale)) {
                return true;
            }
        }
        
        return false; 
    }
         
}