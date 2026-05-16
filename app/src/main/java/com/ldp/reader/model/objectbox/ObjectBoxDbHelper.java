package com.ldp.reader.model.objectbox;

import com.ldp.reader.App;

import io.objectbox.BoxStore;

public class ObjectBoxDbHelper {
    private static volatile ObjectBoxDbHelper sInstance;

    private final BoxStore store;

    private ObjectBoxDbHelper() {
        store = MyObjectBox.builder()
                .androidContext(App.getContext())
                .build();
    }

    public static ObjectBoxDbHelper getInstance() {
        if (sInstance == null) {
            synchronized (ObjectBoxDbHelper.class) {
                if (sInstance == null) {
                    sInstance = new ObjectBoxDbHelper();
                }
            }
        }
        return sInstance;
    }

    public BoxStore getStore() {
        return store;
    }
}
