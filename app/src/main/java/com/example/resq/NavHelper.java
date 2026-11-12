package com.example.resq;

import android.app.Activity;
import android.content.Intent;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Makes the bottom bar (Home, My Circle, Donors, Hospitals, Profile) work the same on every screen.
 * The stack is always kept clean:  Home  +  the screen you are on.
 */
public final class NavHelper {

    private NavHelper() { }

    /**
     * @param selectedId which tab to highlight on this screen (for example R.id.nav_donors)
     * @param isTopLevel true for the main screen of the tab, false for a detail page
     *                   (clicking the highlighted tab on a detail page goes back to the tab itself)
     */
    public static void setup(Activity activity, int selectedId, boolean isTopLevel) {
        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        if (nav == null) return;

        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId && isTopLevel) return true;   // already here

            Class<?> target = targetFor(id);
            if (target == null) return false;

            if (id == R.id.nav_home) {
                activity.startActivity(homeIntent(activity));
            } else {
                if (!(activity instanceof MainActivity)) {
                    // first clear everything above Home, so screens do not pile up
                    activity.startActivity(homeIntent(activity));
                }
                activity.startActivity(new Intent(activity, target));
            }
            activity.overridePendingTransition(0, 0);
            return false;    // this screen keeps its own tab highlighted
        });
        nav.setOnItemReselectedListener(item -> { });
    }

    private static Intent homeIntent(Activity activity) {
        Intent home = new Intent(activity, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return home;
    }

    private static Class<?> targetFor(int id) {
        if (id == R.id.nav_home) return MainActivity.class;
        if (id == R.id.nav_circle) return CircleActivity.class;
        if (id == R.id.nav_donors) return DonorsActivity.class;
        if (id == R.id.nav_hospitals) return HospitalsActivity.class;
        if (id == R.id.nav_profile) return ProfileActivity.class;
        return null;
    }
}
