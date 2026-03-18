package com.rox.manager;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;

public class DashboardFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);
        
        MaterialButton btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnOpen.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), WebViewActivity.class);
            startActivity(intent);
        });

        return view;
    }
}
