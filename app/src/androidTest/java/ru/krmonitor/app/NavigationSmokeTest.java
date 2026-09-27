package ru.krmonitor.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NavigationSmokeTest {
    private static final long TIMEOUT=12000;
    private UiDevice device;
    private ActivityScenario<MainActivity> scenario;
    private Context targetContext;
    private File screenshotDir;

    @Before
    public void setUp() throws Exception {
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        targetContext=InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Prevent system permission/settings screens from covering the app during UI checks.
        targetContext.getSharedPreferences("prefs",Context.MODE_PRIVATE)
                .edit()
                .putBoolean("exact_prompted",true)
                .apply();

        if(Build.VERSION.SDK_INT>=33) {
            try {
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .grantRuntimePermission(targetContext.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
            } catch(Exception ignored) {}
        }
        try {
            device.executeShellCommand("appops set "+targetContext.getPackageName()+" SCHEDULE_EXACT_ALARM allow");
        } catch(Exception ignored) {}

        screenshotDir=new File(targetContext.getExternalFilesDir(null),"ui-screenshots");
        deleteChildren(screenshotDir);
        if(!screenshotDir.exists()) assertTrue(screenshotDir.mkdirs());

        scenario=ActivityScenario.launch(MainActivity.class);
        waitForText("КР Навигатор");
        device.waitForIdle();
        sleep(800);
    }

    @After
    public void tearDown() {
        if(scenario!=null) scenario.close();
    }

    @Test
    public void primaryNavigation_isVisibleAndNotClipped() throws Exception {
        screenshot("01_profiles");

        scenario.onActivity(activity -> {
            assertSyncButtonFits(activity);
            assertSearchHintFits(activity);
            assertPhoneBottomNavigationFitsWhenPresent(activity);
        });

        navigateTo("Все КР");
        waitForText("Все КР");
        screenshot("02_all");

        navigateTo("Избранное");
        waitForText("Избранное");
        screenshot("03_favorites");

        navigateTo("История");
        waitForText("История");
        screenshot("04_history");

        // Return to the profile area. Phones use the persistent bottom bar;
        // wide/two-pane layouts use profile rows in the sidebar.
        UiObject2 profiles=device.findObject(By.desc("Профили"));
        if(profiles!=null) {
            profiles.click();
        } else {
            UiObject2 cardiology=device.wait(Until.findObject(By.text("Кардиология")),TIMEOUT);
            assertNotNull("Profile navigation is missing",cardiology);
            cardiology.click();
        }
        device.waitForIdle();
        sleep(500);

        UiObject2 about=device.wait(Until.findObject(By.desc("О приложении")),TIMEOUT);
        assertNotNull("About button is missing",about);
        about.click();
        waitForText("О приложении");
        screenshot("05_about");

        UiObject2 downloads=device.findObject(By.desc("Скачанные КР"));
        if(downloads==null) {
            device.swipe(
                    device.getDisplayWidth()/2,
                    (int)(device.getDisplayHeight()*0.78f),
                    device.getDisplayWidth()/2,
                    (int)(device.getDisplayHeight()*0.28f),
                    18
            );
            device.waitForIdle();
            downloads=device.wait(Until.findObject(By.desc("Скачанные КР")),TIMEOUT);
        }
        assertNotNull("Downloaded KR manager entry is missing",downloads);
        downloads.click();
        assertNotNull(
                "Downloaded KR manager did not open",
                device.wait(Until.findObject(By.desc("Назад к информации о приложении")),TIMEOUT)
        );
        screenshot("06_downloads");

        scenario.onActivity(activity -> assertAllVisibleTextViewsStayInsideWindow(activity));
    }

    private void navigateTo(String label) {
        UiObject2 byDescription=device.findObject(By.desc(label));
        if(byDescription!=null) {
            byDescription.click();
        } else {
            UiObject2 byText=device.wait(Until.findObject(By.text(label)),TIMEOUT);
            assertNotNull("Navigation item is missing: "+label,byText);
            byText.click();
        }
        device.waitForIdle();
        sleep(450);
    }

    private void waitForText(String value) {
        assertNotNull("Expected text not found: "+value,
                device.wait(Until.findObject(By.textContains(value)),TIMEOUT));
    }

    private void screenshot(String name) {
        File file=new File(screenshotDir,name+".png");
        assertTrue("Could not save screenshot "+name,device.takeScreenshot(file));
    }

    private static void assertSearchHintFits(Activity activity) {
        List<TextView> texts=new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(),texts);
        EditText search=null;
        for(TextView tv:texts) {
            if(!(tv instanceof EditText)) continue;
            CharSequence hint=tv.getHint();
            if(hint!=null && hint.toString().contains("Название")) {
                search=(EditText)tv;
                break;
            }
        }
        assertNotNull("Search field is missing",search);

        int available=search.getWidth()-search.getPaddingLeft()-search.getPaddingRight();
        assertTrue("Search field has no content width",available>0);
        String hint=search.getHint()==null?"":search.getHint().toString();
        float measured=search.getPaint().measureText(hint);
        assertTrue("Search hint is clipped: "+hint+" measured="+measured+" available="+available,
                measured<=available+2.5f);
    }

    private static void assertPhoneBottomNavigationFitsWhenPresent(Activity activity) {
        String[] labels={"Все КР","Профили","Избранное","История"};
        View root=activity.getWindow().getDecorView();

        View first=findByDescription(root,labels[0]);
        if(first==null) return; // Two-pane/tablet mode intentionally has no bottom bar.

        for(String label:labels) {
            View item=findByDescription(root,label);
            assertNotNull("Bottom navigation item missing: "+label,item);
            assertFullyInsideRoot(root,item,"Bottom navigation item outside screen: "+label);

            TextView text=findText(item,label);
            assertNotNull("Bottom navigation label missing: "+label,text);
            assertTextFits(text,"Bottom navigation label clipped: "+label);
        }
    }

    private static void assertSyncButtonFits(Activity activity) {
        View root=activity.getWindow().getDecorView();
        View found=findByDescription(root,"Проверить обновления");
        assertNotNull("Sync button is missing",found);
        assertTrue("Sync action is not a TextView",found instanceof TextView);

        TextView sync=(TextView)found;
        assertFullyInsideRoot(root,sync,"Sync button is outside the visible window");

        String value=sync.getText()==null?"":sync.getText().toString();
        if(value.contains("Провер")) {
            assertTrue("Sync label lost the full word 'Проверить': "+value,
                    value.contains("Проверить"));
            assertTextFits(sync,"Sync button label is clipped");
        } else {
            // Compact landscape is intentionally icon-only. The action remains
            // discoverable to accessibility services via contentDescription.
            assertEquals("Compact sync control must be icon-only","↻",value.trim());
        }
    }

    private static void assertAllVisibleTextViewsStayInsideWindow(Activity activity) {
        View root=activity.getWindow().getDecorView();
        List<TextView> texts=new ArrayList<>();
        collectTextViews(root,texts);
        Rect visible=new Rect();
        for(TextView tv:texts) {
            if(tv.getVisibility()!=View.VISIBLE || tv.getWidth()<=0 || tv.getHeight()<=0) continue;
            CharSequence value=tv.getText();
            if(value==null || value.length()==0) continue;

            // A child inside a ScrollView may have VISIBLE state while being
            // legitimately below the viewport. Only inspect text that is
            // actually visible on screen right now.
            if(!tv.getGlobalVisibleRect(visible) || visible.isEmpty()) continue;
            assertFullyInsideRoot(root,tv,"Visible text outside window: "+value);
        }
    }

    private static void assertTextFits(TextView tv,String message) {
        int available=tv.getWidth()-tv.getPaddingLeft()-tv.getPaddingRight();
        assertTrue(message+" (no content width)",available>0);

        if(tv.getLayout()!=null) {
            for(int line=0;line<tv.getLayout().getLineCount();line++) {
                assertEquals(message+" (ellipsized)",0,tv.getLayout().getEllipsisCount(line));
            }
        }

        if(tv.getMaxLines()==1 || tv.getLineCount()<=1) {
            float measured=tv.getPaint().measureText(tv.getText().toString());
            assertTrue(message+" measured="+measured+" available="+available,
                    measured<=available+2.5f);
        }
    }

    private static void assertFullyInsideRoot(View root,View child,String message) {
        Rect rootRect=new Rect();
        Rect childRect=new Rect();
        assertTrue("Root has no visible rect",root.getGlobalVisibleRect(rootRect));
        assertTrue(message+" (not visible)",child.getGlobalVisibleRect(childRect));
        assertTrue(message+" left",childRect.left>=rootRect.left);
        assertTrue(message+" top",childRect.top>=rootRect.top);
        assertTrue(message+" right",childRect.right<=rootRect.right);
        assertTrue(message+" bottom",childRect.bottom<=rootRect.bottom);
    }

    private static View findByDescription(View view,String description) {
        CharSequence d=view.getContentDescription();
        if(d!=null && description.contentEquals(d)) return view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                View found=findByDescription(group.getChildAt(i),description);
                if(found!=null) return found;
            }
        }
        return null;
    }

    private static TextView findText(View view,String text) {
        if(view instanceof TextView) {
            TextView tv=(TextView)view;
            if(text.contentEquals(tv.getText())) return tv;
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                TextView found=findText(group.getChildAt(i),text);
                if(found!=null) return found;
            }
        }
        return null;
    }

    private static void collectTextViews(View view,List<TextView> out) {
        if(view instanceof TextView) out.add((TextView)view);
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) collectTextViews(group.getChildAt(i),out);
        }
    }

    private static void deleteChildren(File dir) {
        if(dir==null || !dir.exists()) return;
        File[] files=dir.listFiles();
        if(files==null) return;
        for(File file:files) {
            if(file.isDirectory()) deleteChildren(file);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
