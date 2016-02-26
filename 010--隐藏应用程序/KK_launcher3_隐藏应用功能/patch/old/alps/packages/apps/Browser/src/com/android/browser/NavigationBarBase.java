/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.browser;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.net.Uri;

import com.android.browser.UrlInputView.UrlInputListener;
import com.mediatek.browser.ext.Extensions;
import com.mediatek.browser.ext.IBrowserSmallFeatureEx;

public class NavigationBarBase extends LinearLayout implements
        OnClickListener, UrlInputListener, OnFocusChangeListener,
        TextWatcher {

    protected BaseUi mBaseUi;
    protected TitleBar mTitleBar;
    protected UiController mUiController;
    protected UrlInputView mUrlInput;
    protected boolean mInVoiceMode = false;

    private ImageView mFavicon;
    private ImageView mLockIcon;
    
    /// M: Browser Small feature plugin. 
    private IBrowserSmallFeatureEx mBrowserSmallFeatureEx = null;
    
    public NavigationBarBase(Context context) {
        super(context);
    }

    public NavigationBarBase(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigationBarBase(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public UrlInputView getUrlInputView() {
        return mUrlInput;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockIcon = (ImageView) findViewById(R.id.lock);
        mFavicon = (ImageView) findViewById(R.id.favicon);
        mUrlInput = (UrlInputView) findViewById(R.id.url);
        mUrlInput.setUrlInputListener(this);
        mUrlInput.setOnFocusChangeListener(this);
        mUrlInput.setSelectAllOnFocus(true);
        mUrlInput.addTextChangedListener(this);
        
        /// M: Operator Feature check whether the url is too long, if yes, show warning dialog. @{
        mBrowserSmallFeatureEx = Extensions.getSmallFeaturePlugin(mContext);
        if (mBrowserSmallFeatureEx.shouldCheckUrlLengthLimit()) {
            mWarningDialog = new AlertDialog.Builder(mContext).create();
            
            //Use to constraint the max word number that user can input.
            InputFilter[] contentFilters = new InputFilter[1];
            final int nLimit = mContext.getResources()
                    .getInteger(com.mediatek.internal.R.integer.max_input_browser_search_limit);
            contentFilters[0] = new InputFilter.LengthFilter(nLimit) {
                public CharSequence filter(CharSequence source, int start, int end,
                        Spanned dest, int dstart, int dend) {

                    int keep = nLimit - (dest.length() - (dend - dstart));
                    if (keep <= 0) {
                        showWarningDialog();
                        return "";
                    } else if (keep >= end - start) {
                        return null;
                    } else {
                          if (keep < source.length()) {
                              showWarningDialog();
                          }
                          return source.subSequence(start, start + keep);
                    }
                }
            };
            mUrlInput.setFilters(contentFilters);
        }
        /// @}
    }

    /**
     * M: show waring dialog when max input reached.
     */
    private AlertDialog mWarningDialog;
    private void showWarningDialog() {
        if (mWarningDialog != null && !mWarningDialog.isShowing()) {
            mWarningDialog.setTitle(R.string.max_input_browser_search_title);
            mWarningDialog.setMessage(this.getContext().getString(R.string.max_input_browser_search));
            mWarningDialog.setButton(this.getContext().getString(R.string.max_input_browser_search_button),
                new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    return;
                }
                });
            mWarningDialog.show();
        }
    }

    public void setTitleBar(TitleBar titleBar) {
        mTitleBar = titleBar;
        mBaseUi = mTitleBar.getUi();
        mUiController = mTitleBar.getUiController();
        mUrlInput.setController(mUiController);
    }

    public void setLock(Drawable d) {
        if (mLockIcon == null) return;
        if (d == null) {
            mLockIcon.setVisibility(View.GONE);
        } else {
            mLockIcon.setImageDrawable(d);
            mLockIcon.setVisibility(View.VISIBLE);
        }
    }

    public void setFavicon(Bitmap icon) {
        if (mFavicon == null) return;
        mFavicon.setImageDrawable(mBaseUi.getFaviconDrawable(icon));
    }

    @Override
    public void onClick(View v) {
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        // if losing focus and not in touch mode, leave as is
        if (hasFocus || view.isInTouchMode() || mUrlInput.needsUpdate()) {
            setFocusState(hasFocus);
        }
        if (hasFocus) {
            mBaseUi.showTitleBar();
        } else if (!mUrlInput.needsUpdate()) {
            mUrlInput.dismissDropDown();
            mUrlInput.hideIME();
            if (mUrlInput.getText().length() == 0) {
                Tab currentTab = mUiController.getTabControl().getCurrentTab();
                if (currentTab != null) {
                    setDisplayTitle(currentTab.getUrl());
                }
            }
            mBaseUi.suggestHideTitleBar();
        }
        mUrlInput.clearNeedsUpdate();
    }

    protected void setFocusState(boolean focus) {
    }

    public boolean isEditingUrl() {
        return mUrlInput.hasFocus();
    }

    void stopEditingUrl() {
        WebView currentTopWebView = mUiController.getCurrentTopWebView();
        if (currentTopWebView != null) {
            currentTopWebView.requestFocus();
        }
    }

    void setDisplayTitle(String title) {
        if (!isEditingUrl()) {
            /// M: add for site navigation
            ///If it starts with "about:blank" then display "about:blank", for example about:blank1
            ///Site navigation has address like about:blank+number(1~9) @{
            if (title.startsWith("about:blank")) {
                mUrlInput.setText("about:blank", false);
            } else {
                mUrlInput.setText(title, false);
            }
            /// @}
        }
    }

    void setIncognitoMode(boolean incognito) {
        mUrlInput.setIncognitoMode(incognito);
    }

    void clearCompletions() {
        mUrlInput.dismissDropDown();
    }

 // UrlInputListener implementation

    /**
     * callback from suggestion dropdown
     * user selected a suggestion
     */
    @Override
    public void onAction(String text, String extra, String source) {
        stopEditingUrl();
        if (UrlInputView.TYPED.equals(source)) {
            String url = UrlUtils.smartUrlFilter(text, false);
            Tab t = mBaseUi.getActiveTab();
            // Only shortcut javascript URIs for now, as there is special
            // logic in UrlHandler for other schemas
            if (url != null && t != null && url.startsWith("javascript:")) {
                mUiController.loadUrl(t, url);
                setDisplayTitle(text);
                return;
            }
        }
        Intent i = new Intent();
        if (text != null && text.startsWith("content://")) {
            i.setAction(Intent.ACTION_VIEW);
            i.setData(Uri.parse(text));
        /// M: add for rtsp:// @ {
        } else if (text != null && text.startsWith("rtsp://")) {
            i.setAction(Intent.ACTION_VIEW);
            i.setData(Uri.parse(text.replaceAll(" ", "%20")));
        /// @ }
        /// M: add for wtai:// @ {
        } else if (text != null && text.startsWith(UrlHandler.SCHEME_WTAI_MC)) {
            i.setAction(Intent.ACTION_VIEW);
            i.setData(Uri.parse(WebView.SCHEME_TEL + text.substring(UrlHandler.SCHEME_WTAI_MC.length())));
        /// @ }
        } else {
/*
            String action = null;
            if (UrlInputView.VOICE.equals(source)) {
                action = RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS;
                source = null;
            } else {
                action = Intent.ACTION_SEARCH;
            }
*/
            String action = Intent.ACTION_SEARCH;
            i.setAction(action);
            i.putExtra(SearchManager.QUERY, text);
            if (extra != null) {
                i.putExtra(SearchManager.EXTRA_DATA_KEY, extra);
            }
            if (source != null) {
                Bundle appData = new Bundle();
                appData.putString(com.android.common.Search.SOURCE, source);
                i.putExtra(SearchManager.APP_DATA, appData);
            }
        }
        mUiController.handleNewIntent(i);
        setDisplayTitle(text);
    }

    @Override
    public void onDismiss() {
        final Tab currentTab = mBaseUi.getActiveTab();
        mBaseUi.hideTitleBar();
        post(new Runnable() {
            public void run() {
                clearFocus();
                if (currentTab != null) {
                    setDisplayTitle(currentTab.getUrl());
                }
            }
        });
    }

    /**
     * callback from the suggestion dropdown
     * copy text to input field and stay in edit mode
     */
    @Override
    public void onCopySuggestion(String text) {
        mUrlInput.setText(text, true);
        if (text != null) {
            mUrlInput.setSelection(text.length());
        }
    }

    public void setCurrentUrlIsBookmark(boolean isBookmark) {
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent evt) {
        if (evt.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // catch back key in order to do slightly more cleanup than usual
            stopEditingUrl();
            return true;
        }
        return super.dispatchKeyEventPreIme(evt);
    }

    /**
     * called from the Ui when the user wants to edit
     * @param clearInput clear the input field
     */
    void startEditingUrl(boolean clearInput, boolean forceIME) {
        // editing takes preference of progress
        setVisibility(View.VISIBLE);
        if (mTitleBar.useQuickControls()) {
            mTitleBar.getProgressView().setVisibility(View.GONE);
        }
        if (!mUrlInput.hasFocus()) {
            mUrlInput.requestFocus();
        }
        if (clearInput) {
            mUrlInput.setText("");
        }
        if (forceIME) {
            mUrlInput.showIME();
        }
    }

    public void onProgressStarted() {
    }

    public void onProgressStopped() {
    }

    public boolean isMenuShowing() {
        return false;
    }

    public void onTabDataChanged(Tab tab) {
    }

    public void onVoiceResult(String s) {
        startEditingUrl(true, true);
        onCopySuggestion(s);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged(Editable s) { }

}
