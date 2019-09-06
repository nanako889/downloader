package com.qbw.downloader.demo;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.os.Bundle;

import com.qbw.downloader.D;
import com.qbw.l.L;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if(!EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)){
            EasyPermissions.requestPermissions(this,"",1,Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        D.init(this, true);
        D.getInstance().addTask(D.Task.createInstance(this, "https://user-gold-cdn.xitu.io/2017/11/25/15ff090eec3720dc?imageslim", "aaa.jpg"));
    }
}
