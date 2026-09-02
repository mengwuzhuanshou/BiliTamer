package android.widget;

import android.content.Context;
import android.text.Editable;

public class EditText extends TextView {
    public EditText(Context context) { super(context); }
    public void setHint(CharSequence hint) { }
    public void setSingleLine(boolean singleLine) { }
    public void setMinLines(int minLines) { }
    public void addTextChangedListener(android.text.TextWatcher watcher) { }
    public Editable getText() { return null; }
}
