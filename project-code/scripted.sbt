scriptedBufferLog := false

ScriptedPlugin.scriptedSettings

scriptedLaunchOpts <+= version { "-Dplugin.version=" + _ }

