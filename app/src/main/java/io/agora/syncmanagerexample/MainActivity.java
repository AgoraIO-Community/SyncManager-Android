package io.agora.syncmanagerexample;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import io.agora.syncmanagerexample.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private ActivityMainBinding mBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        initView();
    }

    private void initView() {
        mBinding.btnJoinAttMain.setOnClickListener(this);
        mBinding.inputChannelAttMain.setText(Editable.Factory.getInstance().newEditable("sceneId"));
        mBinding.inputUserAttMain.setText(Editable.Factory.getInstance().newEditable("DemoUser001"));
    }

    @Override
    public void onClick(View v) {
        Intent intent = new Intent(this, RoomActivity.class);
        intent.putExtra("channel", mBinding.inputChannelAttMain.getText().toString().trim());
        intent.putExtra("userid", mBinding.inputUserAttMain.getText().toString().trim());
        this.startActivity(intent);
    }
}