package com.akaa.autoclicker.model

enum class ConditionType {
    ALWAYS,             // Direct execution without conditions
    TEXT_EXISTS,        // Match if specific text is visible on screen
    TEXT_NOT_EXISTS,    // Match if specific text is NOT visible on screen
    IMAGE_EXISTS        // Match if specific image pattern is found on screen
}
