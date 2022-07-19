package io.agora.syncmanagerexample;

import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.Scene;
import io.agora.syncmanager.rtm.SceneReference;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;
import io.agora.syncmanagerexample.databinding.ActivityRoomBinding;

public class RoomActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private MyAdapter mAdapter = null;
    private List<Member> mData = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private String channel;
    private String userid;
    private String userObjectId;
    private Boolean isGridLayout;
    private SceneReference sceneRef;
    private static final String MEMBER = "member";

    private ActivityRoomBinding mBinding;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityRoomBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        Intent intent = this.getIntent();
        channel = intent.getStringExtra("channel");
        userid = intent.getStringExtra("userid");


        initView();
        handleSyncManager();

    }

    private void initView() {
        mBinding.channelname.setText(channel);
        mData = new LinkedList<>();
        mAdapter = new MyAdapter((LinkedList<Member>) mData, this);
        mBinding.txtEmpty.setText("No members yet");
        mBinding.listOne.setAdapter(mAdapter);
        mBinding.listOne.setEmptyView(mBinding.txtEmpty);
        mBinding.switchLayout.setOnCheckedChangeListener(this);
    }

    private void handleSyncManager() {
        // Step 1 : join scene
        Scene scene = new Scene();
        scene.setId(channel);
        scene.setUserId(userid);
        Map<String, String> property = new HashMap();
        property.put("type", "2");
        property.put("backgroundId", "1");
        scene.setProperty(property);
        Sync.Instance().createScene(scene, new Sync.Callback() {
            @Override
            public void onSuccess() {

            }

            @Override
            public void onFail(SyncManagerException exception) {

            }
        });
        Sync.Instance().joinScene(channel, joinSceneCallback);
    }

    private Sync.JoinSceneCallback joinSceneCallback = new Sync.JoinSceneCallback() {
        @Override
        public void onSuccess(SceneReference sceneReference) {
            sceneRef = sceneReference;
            Sync.Instance().getScenes(new Sync.DataListCallback() {
                @Override
                public void onSuccess(List<IObject> result) {
                    Log.i(TAG, "getScenes "+ result.size());
                    if(result.size() > 0){
                        for(IObject item : result) {
                            Log.i(TAG, "getScenes: " + item.toString());
                        }
                    }
                }

                @Override
                public void onFail(SyncManagerException exception) {

                }
            });
            // Step 2: Subscribe room property
            sceneRef.subscribe(roomEventListener);
            sceneRef.subscribe("layout", layoutListener);
            // Step 3: Handle Members
            HashMap<String, Object> member = new HashMap<>();
            member.put("id", userid);
            member.put("os", "Android");
            sceneRef.collection(MEMBER).add(member, new Sync.DataItemCallback() {
                @Override
                public void onSuccess(IObject result) {
                    Log.i(TAG, "on add member Success: " + result.getId());
                    userObjectId = result.getId();
                }

                @Override
                public void onFail(SyncManagerException exception) {
                    Log.w(TAG, "on add member Fail: ", exception);
                }
            });
            sceneRef.collection(MEMBER).subscribe(memberEventListener);
            sceneRef.collection(MEMBER).get(new Sync.DataListCallback() {
                @Override
                public void onSuccess(List<IObject> result) {
                    Log.i(TAG, "on get member list Success: " + result.size());
                    runOnUiThread(() -> {
                        for (IObject iObject : result) {
                            Member item = new Member(iObject.getId(), iObject.toString());
                            mAdapter.add(item);
                        }
                    });
                }

                @Override
                public void onFail(SyncManagerException exception) {
                    Log.w(TAG, "on get member list Fail: ", exception);
                }
            });
        }

        @Override
        public void onFail(SyncManagerException exception) {
            Log.e(TAG, "join scene failed! might be rtm param or network issue.");
        }
    };

    @Override
    public void onBackPressed() {
        if(sceneRef == null){
            return;
        }
        sceneRef.collection(MEMBER).delete(userObjectId, new Sync.Callback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "on delete member Success");
            }

            @Override
            public void onFail(SyncManagerException exception) {
                Log.w(TAG, "on delete member Fail: ", exception);
            }
        });
        sceneRef.delete(new Sync.Callback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "on delete scene Success");
            }

            @Override
            public void onFail(SyncManagerException exception) {
                Log.e(TAG, "onFail: ", exception);
            }
        });
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private Sync.EventListener memberEventListener = new Sync.EventListener() {
        @Override
        public void onCreated(IObject item) {
            Log.i(TAG, "on member Created: " + item.toString());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Member member = new Member(item.getId(), item.toString());
                    mAdapter.add(member);
                }
            });
        }

        @Override
        public void onUpdated(IObject item) {
            Log.i(TAG, "on member updated: " + item.getId());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Member member = new Member(item.getId(), item.toString());
                    mAdapter.add(member);
                }
            });
        }

        @Override
        public void onDeleted(IObject item) {
            Log.i(TAG, "on member deleted: " + item.toString());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    for(Member member : mData){
                        if(member.getId().equals(item.getId())){
                            mAdapter.remove(member);
                            return;
                        }
                    }
                }
            });
        }

        @Override
        public void onSubscribeError(SyncManagerException ex) {
            Log.w(TAG, "on member SubscribeError: ", ex);
        }
    };
    private final Sync.EventListener scene1Listener = new Sync.EventListener() {

        @Override
        public void onCreated(IObject item) {

        }

        @Override
        public void onUpdated(IObject item) {

        }

        @Override
        public void onDeleted(IObject item) {
            finish();
        }

        @Override
        public void onSubscribeError(SyncManagerException ex) {

        }
    };

    private final Sync.EventListener layoutListener = new Sync.EventListener() {
        @Override
        public void onCreated(IObject item) {
            Log.i(TAG, "on room property Created: " + item.getId());
        }

        @Override
        public void onUpdated(IObject item) {
            Log.i(TAG, "on room property Created: " + item.getId());
            if (item.getId().contains("layout")) {
                isGridLayout = item.toObject(String.class).equals("grid");
                handler.post(() -> mBinding.switchLayout.setChecked(isGridLayout));
            }
        }

        @Override
        public void onDeleted(IObject item) {
            Log.i(TAG, "on room property Deleted: " + item.toString());
        }

        @Override
        public void onSubscribeError(SyncManagerException ex) {
            Log.w(TAG, "on room event SubscribeError: ", ex);
        }
    };

    private final Sync.EventListener roomEventListener = new Sync.EventListener() {
        @Override
        public void onCreated(IObject item) {
            Log.i(TAG, "on channel1 property Created: " + item.getId());
        }

        @Override
        public void onUpdated(IObject item) {
            Log.i(TAG, "on channel1 room property Created: " + item.getId());
        }

        @Override
        public void onDeleted(IObject item) {
            Log.i(TAG, "on channel1room property Deleted: " + item.toString());
            finish();
        }

        @Override
        public void onSubscribeError(SyncManagerException ex) {
            Log.w(TAG, "on channel1room event SubscribeError: ", ex);
        }
    };

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if(!buttonView.isPressed()) return;
        isGridLayout = isChecked;
        sceneRef.update("layout", isGridLayout ? "grid" : "focus", new Sync.DataItemCallback() {
            @Override
            public void onSuccess(IObject result) {
                Log.i(TAG, "on update layout property Success: " + result.getId());
            }

            @Override
            public void onFail(SyncManagerException exception) {
                Log.w(TAG, "on update layout property Fail: ", exception);
            }
        });
    }
}
