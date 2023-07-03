// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.dialog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import io.github.muntashirakon.ui.R;

@SuppressWarnings("unused")
public class TextInputDialogBuilder {
    @NonNull
    private final Context mContext;
    @NonNull
    private final TextInputLayout mTextInputLayout;
    @NonNull
    private final TextInputEditText mEditText;
    @NonNull
    private final MaterialCheckBox mCheckBox;
    @NonNull
    private final MaterialAlertDialogBuilder mBuilder;

    public interface OnClickListener {
        void onClick(DialogInterface dialog, int which, @Nullable Editable inputText, boolean isChecked);
    }

    @SuppressLint("InflateParams")
    public TextInputDialogBuilder(@NonNull Context context, @Nullable CharSequence inputTextLabel) {
        mContext = context;
        View view = View.inflate(context, R.layout.dialog_text_input, null);
        mTextInputLayout = view.findViewById(android.R.id.text1);
        mTextInputLayout.setHint(inputTextLabel);
        mEditText = view.findViewById(android.R.id.input);
        mCheckBox = view.findViewById(android.R.id.checkbox);
        mCheckBox.setVisibility(View.GONE);
        mBuilder = new MaterialAlertDialogBuilder(context).setView(view);
    }

    @SuppressLint("InflateParams")
    public TextInputDialogBuilder(@NonNull Context context, @StringRes int inputTextLabel) {
        this(context, context.getText(inputTextLabel));
    }

    public TextInputDialogBuilder setTitle(@Nullable CharSequence title) {
        mBuilder.setTitle(title);
        return this;
    }

    public TextInputDialogBuilder setTitle(@StringRes int title) {
        mBuilder.setTitle(title);
        return this;
    }

    public TextInputDialogBuilder setInputText(@Nullable CharSequence inputText) {
        mEditText.setText(inputText);
        return this;
    }

    public TextInputDialogBuilder setInputText(@StringRes int inputText) {
        mEditText.setText(inputText);
        return this;
    }

    public TextInputDialogBuilder setHelperText(@Nullable CharSequence helperText) {
        mTextInputLayout.setHelperText(helperText);
        return this;
    }

    public TextInputDialogBuilder setHelperText(@StringRes int helperText) {
        mTextInputLayout.setHelperText(mContext.getText(helperText));
        return this;
    }

    public TextInputDialogBuilder setChecked(boolean checked) {
        mCheckBox.setChecked(checked);
        return this;
    }

    public TextInputDialogBuilder setCheckboxLabel(@Nullable CharSequence checkboxLabel) {
        if (checkboxLabel != null) {
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setText(checkboxLabel);
        } else mCheckBox.setVisibility(View.GONE);
        return this;
    }

    public TextInputDialogBuilder setCheckboxLabel(@StringRes int checkboxLabel) {
        if (checkboxLabel != 0) {
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setText(checkboxLabel);
        } else mCheckBox.setVisibility(View.GONE);
        return this;
    }

    public TextInputDialogBuilder setPositiveButton(@StringRes int textId, @Nullable OnClickListener listener) {
        mBuilder.setPositiveButton(textId, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    public TextInputDialogBuilder setPositiveButton(@NonNull CharSequence text, @Nullable OnClickListener listener) {
        mBuilder.setPositiveButton(text, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    public TextInputDialogBuilder setNegativeButton(@StringRes int textId, @Nullable OnClickListener listener) {
        mBuilder.setNegativeButton(textId, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    public TextInputDialogBuilder setNegativeButton(@NonNull CharSequence text, @Nullable OnClickListener listener) {
        mBuilder.setNegativeButton(text, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    public TextInputDialogBuilder setNeutralButton(@StringRes int textId, @Nullable OnClickListener listener) {
        mBuilder.setNeutralButton(textId, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    public TextInputDialogBuilder setNeutralButton(@NonNull CharSequence text, @Nullable OnClickListener listener) {
        mBuilder.setNeutralButton(text, (dialog, which) -> {
            if (listener != null) listener.onClick(dialog, which, mEditText.getText(), mCheckBox.isChecked());
        });
        return this;
    }

    @Nullable
    public Editable getInputText() {
        return mEditText.getText();
    }

    @NonNull
    public AlertDialog create() {
        return mBuilder.create();
    }

    public void show() {
        create().show();
    }
}
