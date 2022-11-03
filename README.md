
# SyncManager

一个轻量场景数据同步管理，提供Scence房间管理，以及collection合集做房间内多种业务数据管理。
同时也实现了[iOS版](https://github.com/AgoraIO-Community/SyncManager-iOS)



## 集成
在项目根目录下build.gralde添加
```gradle
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```
在module下build.gradle里添加
```gradle
dependencies {
	implementation 'com.github.AgoraIO-Community:SyncManager-Android:2.0.7'
}
```

## 使用说明

### 1. 初始化

```java
Map<String, String> params=new HashMap<>();
// sync的唯一标识，可以是uuid，互通需要保持appId一致
params.put("appid",<==SYNC APP ID==>); 
// 默认频道，一个defaultChannel对应一个场景的房间列表
params.put("defaultChannel", <==DEFAULT CHANNEL==>);
Sync.Instance().init(this,params,new Sync.Callback(){
	@Override
	public void onSuccess(){

	}

	@Override
	public void onFail(SyncManagerException exception){
		Log.d(TAG,"SyncManager init failed! "+ exception);
	}
});
```
### 2. 房间管理
#### 2.0 房间数据结构
房间的数据结构需要自己定义，尽量避免嵌套，如以下结构。为了方便说明如何使用，下面会继续使用这个结构来说明其他api的使用。
```java
class RoomInfo {
	public String roomId;
	public String roomName;
	public String background;
	public String ownerUserId;
}
```

#### 2.1 创建房间

```java

// 演示数据
RoomInfo roomInfo = new RoomInfo();
roomInfo.roomId = "123456";
roomInfo.roomName = "天天向上";
roomInfo.background = "bg01.png";
roomInfo.ownerUserId = "000000";


String sceneId = roomInfo.roomId;
String sceneUserId = roomInfo.ownerUserId;

Scene scene = new Scene(); 
scene.setId(sceneId);// 房间id 
scene.setUserId(userid); // 房间创建者

// 设置房间数据RoomInfo > property map
Map<String, String> property = new HashMap(); 
property.put("roomId", roomInfo.roomId); 
property.put("roomName", roomInfo.roomName);
property.put("background", roomInfo.background);
property.put("ownerUserId", roomInfo.ownerUserId);
scene.setProperty(property); 

Sync.Instance().createScene(scene, new Sync.Callback() {
	@Override
	public void onSuccess() {
	
	}
    
	@Override
	public void onFail(SyncManagerException exception) {
	}
});
```

#### 2.2 获取房间列表

```java
Sync.Instance().getScenes(new Sync.DataListCallback() {
    @Override
    public void onSuccess(List<IObject> result) {
        Log.i(TAG, "getScenes "+ result.size());
        List<RoomInfo> sceneList = new ArrayList<>();
        if(result.size() > 0){
            for(IObject item : result) {
            		// 将IObject里的房间信息获取出来
                RoomInfo scene = item.toObject(RoomInfo.class);
                sceneList.add(scene);
            }
        }
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});
```

#### 2.3 加入房间
```java
// sceneId：创建房间时Scene.setId()设置的值
Sync.Instance().joinScene(sceneId, new Sync.JoinSceneCallback() {
    @Override
    public void onSuccess(SceneReference sceneReference) {
        // sceneReference需要保存成全局变量或者缓存起来用做下面api的调用
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});
```

#### 2.4. 更新房间数据
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

sceneReference.update("roomName", "好好学习", new Sync.DataItemCallback() {
    @Override
    public void onSuccess(IObject result) {
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});
```


#### 2.5 监听房间变化
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

sceneReference.subscribe(new Sync.EventListener() {
    @Override
    public void onCreated(IObject item) {
        // do nothing. 这个回调是没用的，不要在这里写代码！
    }
    @Override
    public void onUpdated(IObject item) {
        // 数据更新
    }
    @Override
    public void onDeleted(IObject item) {
        // 房间销毁
    }
    @Override
    public void onSubscribeError(SyncManagerException ex) {
    }
});

```

### 3. Collection管理

Collection可以用来做房间内各种业务数据的管理，可以理解成一个数据结构对应一个collection。

#### 3.0 Collection Key和数据结构

使用collection需要自已指定一个唯一的Collection Key，以及这个Key所对应collection保存的数据结构。下面以用户信息为例。
```java
public static final String COLLECTION_KEY_USER_INFO = "userInfoList";

class UserInfo {
	public String userId;
	public String userName;
	public String avatar;
	
	// 不是数据结构的一部分，用做数据更新删除操作
	public String objecteId;
}

```

#### 3.1 添加数据
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

// 演示数据
final UserInfo userA = new UserInfo();
userA.userId = "112233";
userA.userName = "A";
userA.avatar = "avatarA.png";

sceneReference.collection(COLLECTION_KEY_USER_INFO).add(userA, new Sync.DataItemCallback() {
    @Override
    public void onSuccess(IObject result) {
      // 注意：这里的objectId是iObject.id，保存这个id是用于后面的更新删除操作
    	userA.objectId = result.getId();
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});

```

#### 3.2 获取数据
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

sceneReference.collection(COLLECTION_KEY_USER_INFO).get(new Sync.DataListCallback() {
    @Override
    public void onSuccess(List<IObject> result) {
    		// 注意：这里不是ui线程，需要切换到ui线程才能更新ui
    		List<UserInfo> list = new ArrayList<>();
        for (IObject iObject : result) {
            UserInfo userInfo = iObject.toObject(UserInfo.class);
            userInfo.objectId = iObject.getId();
            list.add(userInfo);
        }
        
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});
```


#### 3.3 更新数据
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

// 已有数据，新增返回或者获取数据返回，这里的objectId必须要有
UserInfo userA = new UserInfo();
userA.userId = "112233";
userA.userName = "A";
userA.avatar = "avatarA.png";
userA.objectId = "xxxxxxx";

// 修改名字
userA.userName = "B";

String objectId = userA.objectId;

sceneReference.collection(COLLECTION_KEY_USER_INFO).update(objectId, userA, new Sync.Callback() {
    @Override
    public void onSuccess() {
    
    }
    @Override
    public void onFail(SyncManagerException exception) {
    
    }
});
```


#### 3.4 删除数据
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

// 已有数据，新增返回或者获取数据返回，这里的objectId必须要有
UserInfo userA = new UserInfo();
userA.userId = "112233";
userA.userName = "A";
userA.avatar = "avatarA.png";
userA.objectId = "xxxxxxx";

sceneReference.collection(COLLECTION_KEY_USER_INFO).delete("{objectId}", new Sync.Callback() {
    @Override
    public void onSuccess() {
    }
    @Override
    public void onFail(SyncManagerException exception) {
    }
});
```


#### 3.5 监听数据变化
```java
// 步骤2.3加入房间后获取到的sceneReference
SceneReference sceneReference;

sceneReference.collection(COLLECTION_KEY_USER_INFO).subscribe(new Sync.EventListener() {
    @Override
    public void onCreated(IObject item) {
        // do nothing. 这个回调是没用的，不要在这里写代码！
    }
    @Override
    public void onUpdated(IObject item) {
        // 新增和改变都走onUpdated！！
        UserInfo userInfo = item.toObject(UserInfo.class);
				userInfo.objectId = item.getId();
    }
    @Override
    public void onDeleted(IObject item) {
        // 删除
    }
    @Override
    public void onSubscribeError(SyncManagerException ex) {
        // 错误
    }
});
```



## 反馈

如果你有任何问题或建议，可以通过 issue 的形式反馈。

## 许可证

示例项目遵守 MIT 许可证。





