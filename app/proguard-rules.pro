# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# R8 включён ради вырезания неиспользуемого кода: без минификации APK весит 34 МБ, с ней
# 17 МБ. Переименование поверх этого экономит 66 КБ, а платить за него приходится тем, что
# любой код, опознающий классы по именам (обходы багов прошивок в AmbilightApplication,
# запуск UsbPermissionGranterCli через app_process), требует отдельного правила и молча
# перестаёт работать в release. Исходники открыты, скрывать в байткоде нечего.
-dontobfuscate

# dadb, libadb-android и sun-security-android не несут собственных consumer-правил (в отличие
# от usb-serial-for-android и conscrypt-android, у тех proguard.txt уже в самом AAR) и разбирают
# протокол ADB/крипто через собственные внутренние классы — сузить до конкретных точек входа
# без глубокого рантайм-тестирования всех ADB-путей рискованно, поэтому просто не трогаем эти
# пакеты, как и сам conscrypt делает для себя.
-keep class dadb.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.** { *; }

# UsbPermissionGranterCli запускается из-под root отдельным процессом по полному имени
# класса: CLASSPATH=<apk> app_process ... (см. UsbRootPermissionHelper.grantSingleDevice).
# Вызовов из кода у main() нет, поэтому R8 удалял класс целиком, и выдача USB-разрешения
# с рутом молча отваливалась в release-сборке.
-keep class com.vasmarfas.UniversalAmbientLight.common.util.UsbPermissionGranterCli {
    public static void main(java.lang.String[]);
}

# AmbilightApplication опознаёт баг прошивки по имени класса в стеке (ProfileVerifier
# бросает NoSuchMethodError там, где framework.jar не соответствует версии Android).
# Обфускация переименовывала класс, и обход переставал срабатывать именно в release.
-keepnames class androidx.profileinstaller.**
