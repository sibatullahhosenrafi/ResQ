package com.example.resq;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;

/** Turns Firebase Authentication exceptions into short, friendly messages. */
public class AuthErrorHelper {

    public static String message(Exception e) {
        if (e instanceof FirebaseAuthInvalidUserException) {
            return "No account found with this email";
        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return "Wrong email or password";
        } else if (e instanceof FirebaseAuthUserCollisionException) {
            return "This email is already registered";
        } else if (e instanceof FirebaseAuthWeakPasswordException) {
            return "Password is too weak (use at least 6 characters)";
        } else if (e instanceof FirebaseNetworkException) {
            return "No internet connection. Please connect and try again";
        }
        return e != null && e.getMessage() != null ? e.getMessage() : "Something went wrong";
    }
}
