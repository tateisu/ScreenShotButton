// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version Ext.androidGradlePluginVersion apply false
    id("org.jetbrains.kotlin.android") version Ext.kotlinVersion apply false

    id("io.gitlab.arturbosch.detekt") version "1.23.5" apply true // true!
}
