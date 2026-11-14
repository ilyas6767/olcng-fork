package xyz.zarazaex.olc.handler

import androidx.core.content.FileProvider

/**
 * Отдельный подкласс, чтобы не конфликтовать с уже объявленным в манифесте FileProvider
 * (менеджер манифестов не позволяет два <provider> с одним именем класса).
 */
class UpdateFileProvider : FileProvider()
