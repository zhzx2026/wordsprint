package com.aidemo.wordsprint;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「粘贴进度码」独立页（v1.0.14 起，从扫码页里的 AlertDialog 搬出来）。
 *
 * 为什么要独立成页：用户实测「扫码页点粘贴进度码 = 直接退出」。根因是扫码页只声明了
 * {@code configChanges="orientation|screenSize"}——弹窗里的输入框一弹键盘就产生
 * keyboard/keyboardHidden 配置变化，系统把**扫码页整个销毁重建**，挂在它窗口上的对话框随之消失，
 * 相机也在重建过程中被释放/重开，看到的就是"点了就退出"。
 * 所以：① 输入这件事放到本页（自己也吃掉键盘配置变化，永远不会因为弹键盘被重建）；
 * ② 扫码页只负责 startActivity，相机走它自己的 onPause/onResume 正常路径；
 * ③ 导入成功时置 {@link #imported}，扫码页 onResume 读到它就收尾退出，不再需要弹窗回调。
 */
public class PasteImportActivity extends Activity {

    /** 扫码页据此决定是否直接结束 */
    private static volatile boolean imported;

    static boolean consumeImported() {
        boolean v = imported;
        imported = false;
        return v;
    }

    private EditText et;
    private TextView status, copyDiag;
    private String lastDiag = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_paste_import);
        et = (EditText) findViewById(R.id.etCode);
        status = (TextView) findViewById(R.id.tvStatus);
        copyDiag = (TextView) findViewById(R.id.btnCopyDiag);
        try {
            TextView ver = (TextView) findViewById(R.id.tvVer);
            if (ver != null) ver.setText(Ui.versionTag(this).trim());
        } catch (Throwable ignored) {}

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnRead).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = Ui.readClipboard(PasteImportActivity.this);
                int n = s == null ? 0 : s.trim().length();
                if (n > 0) {
                    et.setText(s);
                    et.setSelection(et.getText().length());
                    setStatus(getString(R.string.paste_clip_chars, n));
                } else {
                    setStatus(str(R.string.paste_clip_none));
                }
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                et.setText("");
                et.requestFocus();
                setStatus("");
                copyDiag.setVisibility(View.GONE);
            }
        });
        findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doImport(); }
        });
        copyDiag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = Ui.copyText(PasteImportActivity.this, lastDiag);
                toast(ok ? str(R.string.diag_copied) : str(R.string.copy_fail));
            }
        });
    }

    private void doImport() {
        final String t = et.getText() == null ? "" : et.getText().toString();
        if (t.trim().length() == 0) {              // 空的就明说，不硬闯、也不自动读剪贴板
            setStatus(str(R.string.paste_need_input));
            et.requestFocus();
            return;
        }
        setStatus(getString(R.string.import_working));
        copyDiag.setVisibility(View.GONE);
        TransferUi.importText(this, t, new TransferUi.Done() {
            @Override public void done(boolean ok) {
                if (ok) {
                    imported = true;
                    finish();                       // 成功就顺着把扫码页也收掉（它 onResume 里收尾）
                } else {
                    noteFailure();
                }
            }
        });
    }

    /** 失败时把 TransferUi 生成的诊断同时贴到页面下方，别让用户只看到一个弹过的卡片 */
    private void noteFailure() {
        lastDiag = TransferUi.lastNote == null ? "" : TransferUi.lastNote;
        if (lastDiag.length() == 0) lastDiag = str(R.string.import_fail_title);
        setStatus(lastDiag);
        copyDiag.setVisibility(View.VISIBLE);
    }

    private void setStatus(String s) {
        try {
            if (status == null) return;
            if (s == null || s.length() == 0) { status.setVisibility(View.GONE); return; }
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private String str(int res) {
        try { return getString(res); } catch (Throwable t) { return ""; }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
