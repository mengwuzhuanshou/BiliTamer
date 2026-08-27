package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;

public class Activity extends Context {
    protected void onCreate(Bundle savedInstanceState) { }
    public void setContentView(View view) { }
    public void finish() { }
    public Intent getIntent() { return null; }
    public Resources getResources() { return null; }
}
