package com.example.c2wdemo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "friscy_prefs")

class FriscyPreferences(private val context: Context) {

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_SELECTED_IMAGE = stringPreferencesKey("selected_image")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val selectedImage: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_IMAGE]
    }

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setSelectedImage(imageId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_IMAGE] = imageId
        }
    }

    suspend fun clearSelectedImage() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SELECTED_IMAGE)
        }
    }
}
