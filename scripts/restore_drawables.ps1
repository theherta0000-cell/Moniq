$base = 'c:\Users\user\AndroidStudioProjects\Moniq\app\src\main\res\drawable'

function write-file($name, $text) {
  $path = Join-Path $base $name
  $text | Out-File -FilePath $path -Encoding UTF8
  Write-Host "wrote $path"
}

$vec = @'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#000000" android:pathData="M3,3h18v18h-18z"/>
</vector>
'@

$play = @'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#000000" android:pathData="M8,5v14l11-7z"/>
</vector>
'@

$grad = @'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient android:angle="135" android:startColor="#6C63FF" android:centerColor="#7A71FF" android:endColor="#8A84FF" android:type="linear" />
</shape>
'@

$oval = @'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <size android:width="64dp" android:height="64dp" />
    <solid android:color="#EEEEEE" />
</shape>
'@

$ovalAccent = @'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <size android:width="64dp" android:height="64dp" />
    <solid android:color="#FF7A59" />
</shape>
'@

$files = @(
  @{ n = 'home_header_gradient.xml'; c = $grad },
  @{ n = 'icon_bg_circle.xml'; c = $oval },
  @{ n = 'icon_bg_circle_accent.xml'; c = $ovalAccent },
  @{ n = 'ic_album.xml'; c = $vec },
  @{ n = 'ic_arrow_forward.xml'; c = $vec },
  @{ n = 'ic_discover.xml'; c = $vec },
  @{ n = 'ic_favourite.xml'; c = $vec },
  @{ n = 'ic_lock.xml'; c = $vec },
  @{ n = 'ic_login.xml'; c = $vec },
  @{ n = 'ic_music_note.xml'; c = $vec },
  @{ n = 'ic_music_placeholder.xml'; c = $vec },
  @{ n = 'ic_person.xml'; c = $vec },
  @{ n = 'ic_play.xml'; c = $play },
  @{ n = 'ic_playlist.xml'; c = $vec },
  @{ n = 'ic_search.xml'; c = $vec },
  @{ n = 'ic_server.xml'; c = $vec },
  @{ n = 'ic_settings.xml'; c = $vec },
  @{ n = 'ic_shuffle.xml'; c = $vec },
  @{ n = 'ic_trending.xml'; c = $vec },
  @{ n = 'login_gradient_bg.xml'; c = $grad },
  @{ n = 'placeholder_gradient.xml'; c = $grad }
)

foreach ($f in $files) {
  write-file $f.n $f.c
}
