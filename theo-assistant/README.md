# Theo's Assistant

A small Android app for Theo that stands in for the old Google Assistant. He opens it, it listens, and it answers by playing a real recording of the Assistant for that request. Anything without a recording gets a short spoken line in a British Google voice. Coin, dice, pick a number, rock paper scissors and the time are worked out live.

## Get the APK

Every push that touches `theo-assistant/` builds a signed APK on GitHub Actions and publishes it as the **theo-assistant-latest** release. Install it with:

    adb install -r theo-assistant.apk

All builds are signed with `keystore/theo.jks`, so a new build installs over the old one on any phone.

## Adding the real Assistant recordings

Recordings are not stored in git. Each answer has a folder on the phone, named by its id:

    /sdcard/Android/media/com.theo.assistant/clips/<id>/

Put any number of audio files (`.mp3`, `.m4a`, `.ogg`, `.opus`, `.wav`, `.aac`) or screen recordings (`.mp4`, `.webm`, `.3gp`, `.mkv`) in it. Videos play full screen, so Theo sees the Assistant's animation too, for example:

    adb shell mkdir -p /sdcard/Android/media/com.theo.assistant/clips/sing_song
    adb push sing_song_1.m4a sing_song_2.m4a /sdcard/Android/media/com.theo.assistant/clips/sing_song/

The app picks one at random each time, never the same one twice in a row. New files work straight away, with no reinstall. To copy everything from one phone to a new one:

    adb pull /sdcard/Android/media/com.theo.assistant/clips clips
    adb push clips /sdcard/Android/media/com.theo.assistant/

The `unknown` folder is played when the app didn't understand.

You can also record from inside the app. Press and hold **Hi Theo!** to open the grown-ups screen, then tap an answer to start recording from the microphone and tap again to stop. Press and hold an answer to hear its newest recording.

## Answer ids

`sing_song` `happy_birthday` `lullaby` `twinkle` `serenade` `fox` `snowman` `dogs_out` `pineapple` `rap` `beatbox` `dance` `party` `animal_cow` `animal_dog` `animal_cat` `animal_lion` `animal_duck` `animal_pig` `animal_sounds` `favourite_animal` `joke` `knock_knock` `chicken` `laugh` `tickle` `fart` `alligator` `self_destruct` `sandwich` `clean_room` `homework` `bored` `nickname` `story` `game` `riddle` `tongue_twister` `fact` `how_old` `favourite_colour` `favourite_food` `sleep` `where_live` `friends` `human` `love_you` `superhero` `mirror` `force` `easter_eggs` `good_morning` `good_night` `hello` `how_are_you` `thank_you`, plus `unknown`.

The words that trigger each one are in `app/src/main/assets/intents.json`. The longest phrase that was fully heard wins, so "sing happy birthday" goes to `happy_birthday` rather than `sing_song`.
