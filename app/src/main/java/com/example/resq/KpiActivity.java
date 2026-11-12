package com.example.resq;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.resq.databinding.ActivityKpiBinding;
import com.example.resq.databinding.ItemKpiStepBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

/**
 * "My Preparedness" screen (opened from Profile): a progress ring with the readiness score,
 * Completed / Remaining cards and a checklist. Tapping a step opens the screen that completes it.
 */
public class KpiActivity extends AppCompatActivity {

    private ActivityKpiBinding binding;
    private FirebaseUser user;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityKpiBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_profile, false);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {          // safety: not logged in
            finish();
            return;
        }
        prefs = getSharedPreferences("resq", Context.MODE_PRIVATE);
        binding.btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (user == null) return;
        render();                                                  // instant, from the phone
        Readiness.refreshCloud(prefs, user.getUid(), this::render); // then update from the cloud
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;

        List<Readiness.Step> steps = Readiness.compute(prefs, user.getUid());
        int done = Readiness.doneCount(steps);
        int left = steps.size() - done;
        int pct = Readiness.percent(steps);

        binding.ring.setProgressCompat(pct, true);
        binding.tvPercent.setText(pct + "%");
        binding.tvDone.setText(done + (done == 1 ? " step" : " steps"));
        binding.tvLeft.setText(left + (left == 1 ? " step" : " steps"));
        binding.tvMessage.setText(left == 0
                ? "You are fully prepared. Well done!"
                : "Finish the steps below so your circle can help you faster.");

        binding.stepsContainer.removeAllViews();
        for (Readiness.Step s : steps) {
            ItemKpiStepBinding row = ItemKpiStepBinding.inflate(
                    getLayoutInflater(), binding.stepsContainer, false);
            row.stepTitle.setText(s.title);
            row.stepHint.setText(s.hint);
            row.stepIconBg.setBackgroundResource(
                    s.done ? R.drawable.bg_kpi_done_circle : R.drawable.bg_kpi_todo_circle);
            row.stepCheck.setVisibility(s.done ? View.VISIBLE : View.GONE);
            row.getRoot().setOnClickListener(v -> open(s.goTo));
            binding.stepsContainer.addView(row.getRoot());
        }
    }

    private void open(int goTo) {
        switch (goTo) {
            case Readiness.GO_CIRCLE:
                startActivity(new Intent(this, CircleActivity.class));
                break;
            case Readiness.GO_DONOR:
                startActivity(new Intent(this, DonorRegisterActivity.class));
                break;
            case Readiness.GO_HOSPITAL:
                startActivity(new Intent(this, HospitalsActivity.class));
                break;
            default:
                finish();      // Profile is the screen underneath
                break;
        }
    }
}
