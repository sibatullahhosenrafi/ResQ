package com.example.resq;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.example.resq.databinding.ActivityFirstAidBinding;

import java.util.ArrayList;
import java.util.List;

public class FirstAidActivity extends AppCompatActivity {

    public static final String EXTRA_SOURCE = "extra_source";
    private static final String TAG = "ResQ_Lifecycle";
    private static final String S_GRID = "state_is_grid";
    private boolean isGrid = false;   // false = LinearLayoutManager, true = GridLayoutManager

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityFirstAidBinding binding = ActivityFirstAidBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_home, false);
        life("onCreate");

        // Read the data sent through the Intent (putExtra / getStringExtra)
        Intent received = getIntent();
        String source = received.getStringExtra(EXTRA_SOURCE);
        if (source != null) {
            setTitle("First Aid (from " + source + ")");
        }

        List<FirstAidTopic> topics = new ArrayList<>();
        topics.add(new FirstAidTopic("Road accident: first steps",
                "1. Make the scene safe: switch off engines, turn on hazard lights and warn other drivers. Don't put yourself in danger.\n"
                + "2. Call 999. Give the exact place, how many people are hurt and whether anyone is trapped.\n"
                + "3. Check for fire, fuel smell or leaking fuel. Move people away only if there is danger.\n"
                + "4. Don't move an injured person unless there is fire or other danger. Don't remove a helmet unless the airway is blocked.\n"
                + "5. Check breathing. If the person is not breathing, start CPR. Stop any severe bleeding by pressing on the wound.\n"
                + "6. Stay with the person, keep them warm and calm, and tell the ambulance team what happened."));
        topics.add(new FirstAidTopic("Severe bleeding",
                "1. Call emergency services.\n2. Press firmly on the wound with a clean cloth.\n"
                + "3. Don't remove the cloth if soaked; add more on top.\n4. Keep the person lying down and warm."));
        topics.add(new FirstAidTopic("Burns",
                "1. Cool the burn under cool running water for 20 minutes.\n2. Remove rings or tight items near it.\n"
                + "3. Cover loosely with clean cling film or a non-stick dressing.\n4. Don't use ice, butter or toothpaste."));
        topics.add(new FirstAidTopic("Choking (adult)",
                "1. Encourage coughing.\n2. If it fails, give up to 5 firm back blows between the shoulder blades.\n"
                + "3. Then give up to 5 abdominal thrusts.\n4. Alternate; call emergency services if it doesn't clear."));
        topics.add(new FirstAidTopic("CPR (not breathing)",
                "1. Call emergency services.\n2. Place hands on the centre of the chest.\n"
                + "3. Push hard and fast: about 100-120 per minute, 5-6 cm deep.\n4. Continue until help arrives."));
        topics.add(new FirstAidTopic("Fainting",
                "1. Lay the person flat and raise their legs.\n2. Loosen tight clothing.\n"
                + "3. Check breathing; if they don't recover quickly, call emergency services."));

        topics.add(new FirstAidTopic("Broken bone (fracture)",
                "1. Keep the person still and the injured part as it is. Don't try to straighten it.\n"
                + "2. Support the limb with a splint (a stick or folded newspaper, padded with cloth) that also holds the joints above and below.\n"
                + "3. Put a cold pack or ice wrapped in cloth on the area, never directly on the skin.\n"
                + "4. If the bone is sticking out, cover the wound with a clean dressing and press around it, not on the bone.\n"
                + "5. Get to a hospital. Call 999 if it is the thigh, hip, back, neck or head, or if the person is in shock."));
        topics.add(new FirstAidTopic("Head injury",
                "1. Keep the person still and calm. Press a cold cloth on any swelling for 10 minutes.\n"
                + "2. If there is bleeding, press gently with a clean cloth. Don't press hard if the skull may be broken.\n"
                + "3. Don't give food or drink. Stay with the person and watch them.\n"
                + "4. Call 999 at once for: loss of consciousness, vomiting, confusion, strong sleepiness, a fit, fluid from the ears or nose, or weakness in the arms or legs."));
        topics.add(new FirstAidTopic("Neck or back injury",
                "1. Don't move the person unless there is fire or another immediate danger.\n"
                + "2. Tell them to stay still. Hold their head steady with both hands in the position you found it.\n"
                + "3. Call 999 and keep holding the head until the ambulance team takes over.\n"
                + "4. If they stop breathing or vomit and you must turn them, roll the head, neck and body together as one piece."));
        topics.add(new FirstAidTopic("Sprain or strain",
                "1. Rest: stop using the injured part.\n"
                + "2. Ice: put a cold pack wrapped in cloth on it for 15 to 20 minutes, several times a day.\n"
                + "3. Compress with an elastic bandage, firm but not tight.\n"
                + "4. Elevate the limb above the level of the heart.\n"
                + "5. See a doctor if the person can't put weight on it, or there is strong pain, numbness or obvious deformity."));
        topics.add(new FirstAidTopic("Electric shock",
                "1. Don't touch the person while they may still be in contact with the current.\n"
                + "2. Switch off the power at the main switch if you can. If not, push the person away with something dry that does not conduct, like a wooden stick.\n"
                + "3. Call 999, even if the person seems fine.\n"
                + "4. Check breathing. If they are not breathing, start CPR. Cool any burns with running water and cover them loosely."));
        topics.add(new FirstAidTopic("Drowning",
                "1. Don't jump in if you can't swim well. Reach with a stick or throw something that floats.\n"
                + "2. Call 999 or ask someone else to call.\n"
                + "3. Once out of the water, check breathing. If not breathing, give 5 rescue breaths if you know how, then start CPR.\n"
                + "4. If breathing, lay the person on their side, remove wet clothes and keep them warm. Always take them to a hospital."));
        topics.add(new FirstAidTopic("Snake bite",
                "1. Keep the person calm and still. Moving makes venom spread faster.\n"
                + "2. Keep the bitten limb still, at or just below heart level. Remove rings and tight items before it swells.\n"
                + "3. Don't cut the bite, suck the venom, tie a tight band or apply ice.\n"
                + "4. Take the person to a hospital quickly, where antivenom is given. Note what the snake looked like, but don't try to catch it."));

        // (5) Explicit intent back to the home screen
        binding.btnBackHome.setOnClickListener(v -> {
            Intent home = new Intent(this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
            finish();
        });

        // LayoutManager decides the arrangement: same adapter + data, different layout
        binding.rvFirstAid.setAdapter(new FirstAidAdapter(topics));
        // no cross-fade when a card opens/closes: it made the text flicker / appear and disappear
        if (binding.rvFirstAid.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) binding.rvFirstAid.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        // keep the chosen layout after a rotation
        isGrid = savedInstanceState != null && savedInstanceState.getBoolean(S_GRID, false);
        applyLayout(binding);

        binding.btnToggleLayout.setOnClickListener(v -> {
            isGrid = !isGrid;
            applyLayout(binding);
        });
    }

    /** Sets the layout manager and the icon: the icon shows the layout you get by tapping it. */
    private void applyLayout(ActivityFirstAidBinding binding) {
        if (isGrid) {
            // Staggered grid: every card keeps its OWN height, so opening one card does not
            // stretch (and show an empty white box for) the card beside it.
            StaggeredGridLayoutManager lm = new StaggeredGridLayoutManager(
                    getResources().getInteger(R.integer.grid_columns), StaggeredGridLayoutManager.VERTICAL);
            lm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_NONE);
            binding.rvFirstAid.setLayoutManager(lm);
            binding.btnToggleLayout.setImageResource(R.drawable.ic_view_list);
            binding.btnToggleLayout.setContentDescription("Switch to list view");
        } else {
            binding.rvFirstAid.setLayoutManager(new LinearLayoutManager(this));
            binding.btnToggleLayout.setImageResource(R.drawable.ic_view_grid);
            binding.btnToggleLayout.setContentDescription("Switch to grid view");
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(S_GRID, isGrid);
    }

    // ---------- Activity lifecycle methods ----------
    @Override
    protected void onStart() {
        super.onStart();
        life("onStart");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        life("onRestart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        life("onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        life("onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        life("onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        life("onDestroy");
    }

    private void life(String name) {
        Log.d(TAG, "FirstAidActivity " + name + " called");
    }
}
