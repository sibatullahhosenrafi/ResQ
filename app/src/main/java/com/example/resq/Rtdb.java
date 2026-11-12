package com.example.resq;

import com.google.firebase.database.FirebaseDatabase;

/** Gives the Realtime Database of this project (live location sharing). */
public final class Rtdb {

    public static final String URL =
            "https://resq-83464-default-rtdb.asia-southeast1.firebasedatabase.app";

    private Rtdb() { }

    public static FirebaseDatabase get() {
        return FirebaseDatabase.getInstance(URL);
    }
}
