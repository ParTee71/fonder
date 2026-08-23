# Behåll Room-genererade klasser
-keep class androidx.room.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# PdfBox-Android (avräkningsnota-importen) refererar en valfri JPEG-2000-avkodare,
# com.gemalto.jp2.JP2Decoder, som inte är ett beroende. R8 vägrar minifiera med en
# saknad klassreferens och fällde releasebygget — men bara releasebygget, eftersom
# CI:s debugbygge inte kör R8. Felet låg därför latent från att pdfbox lades in tills
# första releasen som tog sig förbi signeringssteget.
#
# -dontwarn, inte beroendet: appen extraherar **text** ur PDF:er, aldrig bilder, så
# JPXFilter.readJPX nås inte. Att dra in jp2-android vore att packa ett bildbibliotek
# för en kodväg appen inte använder. Beteendet är oförändrat — klassen saknades redan.
-dontwarn com.gemalto.jp2.**
