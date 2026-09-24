package com.ts.avm.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class CmdBean implements Parcelable {
    private int header;
    private int payLoadSize;
    private int srcId;
    private int targetId;
    private int transferId;
    private int id;
    private int type;
    private int getOrSet;
    private int priority;
    private int flags;
    private int[] value;

    public CmdBean() {}

    protected CmdBean(Parcel in) {
        header = in.readInt();
        payLoadSize = in.readInt();
        srcId = in.readInt();
        targetId = in.readInt();
        transferId = in.readInt();
        id = in.readInt();
        type = in.readInt();
        getOrSet = in.readInt();
        priority = in.readInt();
        flags = in.readInt();
        value = in.createIntArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(header);
        dest.writeInt(payLoadSize);
        dest.writeInt(srcId);
        dest.writeInt(targetId);
        dest.writeInt(transferId);
        dest.writeInt(id);
        dest.writeInt(type);
        dest.writeInt(getOrSet);
        dest.writeInt(priority);
        dest.writeInt(flags);
        dest.writeIntArray(value);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CmdBean> CREATOR = new Creator<CmdBean>() {
        @Override
        public CmdBean createFromParcel(Parcel in) {
            return new CmdBean(in);
        }

        @Override
        public CmdBean[] newArray(int size) {
            return new CmdBean[size];
        }
    };

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int[] getValue() { return value; }
    public void setValue(int[] value) { this.value = value; }
}
