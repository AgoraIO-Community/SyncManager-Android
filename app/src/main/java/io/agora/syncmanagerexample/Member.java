package io.agora.syncmanagerexample;

public class Member {
    private int imgId;
    private String name;
    private String id;

    public Member() {}

    public Member(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getImgId() {
        return imgId;
    }

    public String getName() {
        return name;
    }

    public void setImgId(int imgId) {
        this.imgId = imgId;
    }

    public void setName(String name) {
        this.name = name;
    }
}

