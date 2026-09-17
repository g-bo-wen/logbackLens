package cn.gbk.logbacklens.settings

import com.intellij.util.messages.Topic

@JvmField
val LOG_LENS_SETTINGS_TOPIC: Topic<LogLensSettingsListener> = Topic.create(
    "Log Lens application settings changed",
    LogLensSettingsListener::class.java,
)

@JvmField
val LOG_LENS_BINDING_TOPIC: Topic<LogLensBindingListener> = Topic.create(
    "Log Lens project binding changed",
    LogLensBindingListener::class.java,
)
