package io.agora.syncmanagerexample;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.LinkedList;

/**
 * Created by Jay on 2015/9/21 0021.
 */
public class MyAdapter extends BaseAdapter {

    private Context mContext;
    private LinkedList<Member> mData;

    public MyAdapter() {
    }

    public MyAdapter(LinkedList<Member> mData, Context mContext) {
        this.mData = mData;
        this.mContext = mContext;
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder = null;
        if (convertView == null) {
            convertView = LayoutInflater.from(mContext).inflate(R.layout.item_list, parent, false);
            holder = new ViewHolder();
            holder.img_icon = convertView.findViewById(R.id.img_icon);
            holder.txt_content = convertView.findViewById(R.id.txt_content);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        holder.img_icon.setImageResource(R.drawable.avatar);
        holder.txt_content.setText(mData.get(position).getName());
        return convertView;
    }

    //添加一个元素
    public void add(Member member) {
        if (mData == null) {
            mData = new LinkedList<>();
        }
        if(mData.contains(member)){
            return;
        }
        mData.add(member);
        notifyDataSetChanged();
    }

    //往特定位置，添加一个元素
    public void add(int position, Member member){
        if (mData == null) {
            mData = new LinkedList<>();
        }
        mData.add(position, member);
        notifyDataSetChanged();
    }

    public void remove(Member member) {
        if(mData != null) {
            mData.remove(member);
        }
        notifyDataSetChanged();
    }

    public void remove(int position) {
        if(mData != null) {
            mData.remove(position);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        if(mData != null) {
            mData.clear();
        }
        notifyDataSetChanged();
    }





    private class ViewHolder {
        ImageView img_icon;
        TextView txt_content;
    }

}
