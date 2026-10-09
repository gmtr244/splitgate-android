# SplitGate

[English](README.md) · **Türkçe**

Android için kök (root) gerektirmeyen bir DPI atlatma uygulaması.
`VpnService` ile yerel bir "sahte VPN" kurar. Trafiğin başka bir sunucudan geçmez; telefonda işlenir ve normal
şekilde çıkar. Yalnızca DNS sorguları seçtiğin DoH sağlayıcısına gider.

## Ne yapar?

| Özellik | Açıklama |
|---|---|
| **SNI bölme** | TLS ClientHello'yu host adının ortasından iki TCP parçasına böler (`discord.com` → `disco` / `rd.com`). DPI tek pakette adı göremez. |
| **TLS + TCP bölme** | İsteğe bağlı: ClientHello'yu site adının ortasından iki ayrı TLS kaydına böler, iki TCP parçası olarak yollar. ClientHello eksik gelirse yine aynı noktadan TCP bölmesi yapılır. |
| **Sıra bozma (root'suz)** | ClientHello'nun ilk parçası TTL 1 ile gönderilir (uygulamanın kendi soketinde `setsockopt`); DPI'a varmadan ilk router'da ölür, çekirdek onu yeniden gönderir ve sunucuya parçalar sırasız ulaşır. Parçaları birleştiren DPI'lar için. |
| **Ağa göre yöntem** | Wi-Fi ve her mobil operatör kendi yöntemini kullanır. Ağ değişince açık bağlantılar sıfırlanır, uygulamalar yeni ağdan hemen yeniden bağlanır ve o ağ için kayıtlı yöntem kullanılır. |
| **Gerçek kontrol** | Her bağlanışta ve ağ değişiminde seçili yöntem test sitelerinde gerçekten denenir. Uygulama ancak bu başarılıysa *Çalışıyor* der; değilse diğer yöntemleri dener, hiçbiri çalışmazsa olası nedeniyle (DNS, IP engeli, araya girme, DPI) *Bu ağda çalışmıyor* der. |
| **HTTP Host bölme** | Port 80 isteklerinde Host değerini böler. |
| **DNS-over-HTTPS** | UDP ile gönderilen tüm DNS sorguları (sabit yazılmış 8.8.8.8 dahil) DoH ile Quad9 (varsayılan), Cloudflare veya Google üzerinden çözülür. DNS zehirlemesini aşar. AAAA sorguları boş döner (IPv4'e zorlar). |
| **QUIC engeli** | UDP 443 düşürülür; Chrome/Discord TCP+TLS'e düşer ve orada bölebiliriz. |
| **Diğer UDP** | (ör. Discord ses) olduğu gibi iletilir. |

Uygulamanın kendisi VPN dışında tutulur (`addDisallowedApplication`), bu yüzden döngü olmaz.

## Arayüz ve bildirim

- Koyu tema, büyük güç düğmesi, canlı **indirilen / gönderilen** miktarı (B, KB, MB, GB) ve anlık hız,
  aktif bağlantı / bölünen / DNS sayaçları, bağlantı süresi.
- **Ön ayarlar** (Linux sürümü splitgate-linux ile aynı konum belirteçleri): Sadece DNS · Hafif (`midsld`) ·
  SNI başı (`1`, `sni`, varsayılan) · Agresif · **OOB baytlı** (ilk parçadan sonra 1 bayt urgent) · TLS + TCP böl ·
  **Sıra bozma** (`1`) · **Sıra bozma (site adı)** (`midsld`) · **TLS + sıra bozma**. QUIC anahtarı.
  Yöntem ya da QUIC anahtarı değişince hemen uygulanır, yeniden başlatmak gerekmez.
- **Çalışan yönteme kendisi geçsin** (varsayılan açık): seçtiğin ya da testin önerdiği yöntem o ağ için kaydedilir
  (Wi-Fi ya da operatöre göre mobil). Kontrol, yöntemin test sitelerini açmadığını görürse diğer yöntemler denenir;
  çalışan kullanılır ve kaydedilir. Elle seçtiğin yöntem değiştirilmez. Yöntem kartında hangi ağda hangi yöntemin
  kayıtlı olduğu görünür; *Kayıtlı ağları unut* hepsini siler.
- **Rapor:** *Rapor gönder* e-posta uygulamanı geliştiriciye yazılmış bir raporla açar (*Paylaş* başka bir uygulamayla
  gönderir). Raporda uygulama ve Android sürümü, telefon modeli, ağ ve operatör, ayarlar, son test sonuçları ve
  uygulamanın kendi durum satırları bulunur. Girdiğin siteler **bulunmaz**: bağlantı ve DNS satırları çıkarılır.
  E-posta uygulamasında gönder'e basmadan hiçbir şey gönderilmez.
- **DNS sağlayıcısı:** Quad9 / Cloudflare / Google; seçilen birincil olur, diğerleri yedek.
- **Test:** *Yöntemleri dene* her ön ayarı bu ağda gerçek TLS el sıkışmasıyla dener, sertifikayı doğrular,
  en iyisini önerir ve *Önerileni uygula* ile seçer. Denenen siteler butonun üstündeki kutuda; bunları senin için
  engelli olan sitelerle değiştir (en çok 8, örn. `ornek.org, haber.ornek.net`). Varsayılanlar (discord.com,
  roblox.com, x.com, wattpad.com) yalnızca örnektir.
- Katlanır **Günlük** bölümü (varsayılan kapalı): "Kopyala" ve "Temizle".
- **Bildirim:** İlk başlatmada (Android 13+) bildirim izni istenir. Bağlıyken sessiz, kalıcı bir bildirim
  görünür: süre, ↓/↑ toplam ve hız, "Durdur" düğmesi. Bağlantı kapalıyken **hiçbir bildirim yoktur**.
  İzin verilmezse VPN yine çalışır, sadece bildirim görünmez.
- Uygulama ikonu: ortadan bölünmüş kalkan (adaptive ikon).
- **Dil:** varsayılan İngilizce. İlk açılışta dil sorulur; sağ üstteki **🌐** düğmesiyle istediğin zaman Türkçe/İngilizce arasında geçebilirsin. Motor ve servis günlükleri dilden bağımsız İngilizcedir.

## Gizlilik / şifreleme

- Bu uygulama trafiği **kendisi şifrelemez**; bir VPN sunucusuna tünel kurmaz.
- DNS sorguları **şifrelenir** (DoH). HTTPS siteler zaten kendi TLS şifrelemesini kullanır.
- Düz HTTP (port 80) şifresizdir; operatör bağlandığın IP adreslerini görmeye devam eder.
- Günlük yalnızca bellekte tutulur, diske yazılmaz; uygulama kapanınca silinir.

## Kurulum

Deponun **Releases** sayfasından en son **`SplitGate-<sürüm>.apk`** dosyasını indirip telefonunda aç
("bilinmeyen kaynaklardan kuruluma izin ver" çıkarsa onayla). Android 7.0 (API 24) veya üstü gerekir.
Uygulama Play Store'da olmadığı için Android veya Play Protect kurulumu onaylamanı isteyebilir; bu normaldir.

Dosyanın değiştirilmediğini görmek için imza sertifikasını sürüm notlarındaki parmak iziyle karşılaştır:

```bash
apksigner verify --print-certs SplitGate-<sürüm>.apk
```

## Kaynaktan derleme

Uygulamayı kullanmak için buna gerek yok: APK'yı Releases'tan indirmen yeter (yukarıya bak). Bu bölüm kendi kopyasını
derlemek isteyen geliştiriciler için.

JDK 17 veya daha yenisi (CI'da JDK 25) ve Android SDK (platform 36, build-tools 36) gerekir. Derleme Gradle 9.8.1 ve Android Gradle Plugin 9.4.1 kullanır; wrapper Gradle'ı kendisi indirir.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (debug anahtarı, geliştirme için)
./gradlew assembleRelease    # app/build/outputs/apk/release/ (R8 ile küçültülür; keystore.properties varsa imzalanır)
```

`assembleRelease` imzasız, R8 ile küçültülmüş bir APK üretir ve Android imzasız APK'yı kurmaz. Kendi anahtarınla
imzalayabilirsin, ama bu geliştiricinin anahtarı değildir: derlemen farklı bir imza taşır, bu yüzden resmî
sürümleri güncelleyemez (resmî sürümler de seninkini güncelleyemez). Proje kökünde, git tarafından yok sayılan bir
`keystore.properties` dosyası (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) varsa Gradle release
derlemesini onunla imzalar. Anahtarı ve bu dosyayı asla commit'leme. Android Studio'da: proje klasörünü aç, Gradle
sync bitsin, **Build → Build APK(s)** ya da telefon USB ile bağlıyken ▶ (Run).

GitHub Actions iş akışı her push'ta motor testini çalıştırır ve iki sürümü de derler; debug APK, yalnızca test için,
çalıştırmaya `SplitGate-debug-apk` adıyla eklenir (yayın sürümleri yerelde imzalanan derlemeden gelir).

## Test etme

1. Uygulamayı aç → güç düğmesine bas → VPN iznini onayla.
2. **Test** kartında *Yöntemleri dene*'ye bas; önerilen ön ayarı uygula (ya da elle seç). Discord/engelli siteyi aç.
3. Olmazsa DNS sağlayıcısını değiştirip testi tekrarla, QUIC engelini aç/kapat dene.
4. Alt kısımdaki log'da (her dilde İngilizce) her bağlantı için şu tür satırlar görünür:
   - `DNS discord.com (A) ok 41ms`
   - `discord.com:443  TLS OOB split @1,158`
5. Wi-Fi ile mobil veri arasında geçince logda `Network changed (Wi-Fi -> Mobil (…)): reset … connections` ve
   gerekirse otomatik test sonucu görünür.
6. Çalışmazsa **"Kopyala"** ile logu ve Test sonucunu alıp bir hata kaydına ekle.

## Motoru bilgisayarda test etme (Android gerekmez)

JDK 17 veya daha yenisi gerekir.

```bash
./tools/run-selftest.sh
```

Sahte bir "telefon" TCP/UDP istemcisi TUN'a paket yazar; motor gerçek loopback soketlerine çıkar.
Doğrulananlar: checksum'lar, TCP el sıkışma/FIN, paket kaybında yeniden iletim, ClientHello bölme,
DNS, UDP iletimi, QUIC düşürme, gerçek bir TLS sunucusuna karşı strateji sınaması ve DoH istemcisi (SNI, Host
başlığı, bağlantı yeniden kullanımı, chunked cevaplar, yedeğe geçiş).

## Bilinen sınırlar

- **Sadece IPv4** tünellenir. AAAA sorguları boş döndüğü için uygulamalar IPv4'e düşer.
- TCP üzerinden DNS ve DNS-over-TLS (port 853) yakalanmaz; doğrudan çıkar.
- Root olmadan **sahte paket** (gerçeğinden önce sahte bir ClientHello) gönderilemez. Sıra bozma (*Sıra bozma*) root'suz çalışır.
  Hiçbir yöntemin yetmediği ağlarda uygulama çalışıyormuş gibi yapmaz, bunu söyler.
- DPI yalnızca SNI'ya bakmıyorsa (IP engeli, ECH vb.) bu yöntem işe yaramaz.
- Parçaların ayrı paket olarak çıkması işletim sistemine bağlıdır; paketler arasına ön ayara göre 0-3 ms bekleme konur.
- Telefonda *Özel DNS (Private DNS)* ayarı sıkı modda bir ana makine adına ayarlıysa DNS sorguları bu uygulamayı atlayabilir; "Kapalı" veya "Otomatik" bırak.
- Android'de aynı anda yalnızca bir VPN çalışır.

## Proje yapısı

```
app/src/main/java/com/projectgamers/splitgate/
  MainActivity.java        arayüz (güç düğmesi, istatistik, yöntem, test, dil, günlük)
  NetworkId.java           hangi ağdayız; ağa göre kayıtlı yöntem
  AndroidTtl.java          kendi soketimizin TTL'i (Sıra bozma için)
  Report.java              sorun raporu (e-posta / paylaş)
  LocaleHelper.java        uygulama içi dil seçimi
  PowerButton.java         güç düğmesi (özel çizim)
  Fmt.java                 B/KB/MB/GB biçimlendirme
  BypassVpnService.java    VpnService + TUN G/Ç + durum bildirimi + ağ değişimi, otomatik test
  LogBuffer.java           ekrandaki log
  engine/                  saf Java motor (Android'e bağımlı değil)
    TunEngine.java         paket yönlendirme, DNS, UDP
    TcpSession.java        TCP sonlandırma + gerçek soket + ClientHello bölme
    Strategy.java          bölme planı (konum belirteçleri, OOB, sıra bozma)
    Sender.java            planlanan parçaları gönderir (Sıra bozma için TTL)
    Presets.java           hazır ayarlar
    Probe.java             strateji sınaması (gerçek TLS el sıkışması)
    TlsSni.java            SNI ayrıştırıcı
    DohResolver.java       DNS-over-HTTPS
tools/selftest/            JVM testi
app/src/main/res/values*/  çeviriler (strings.xml: İngilizce, values-tr: Türkçe)
```

## Çatallar ve topluluk sürümleri

Çatallar ve topluluk sürümleri GPL kapsamında serbesttir: kaynağı aynı lisansla paylaşıp telif bildirimlerini
korudukları sürece değiştirebilir, dağıtabilir ve satabilirler. Kullanıcılar resmî sürümlerle karıştırmasın diye
lütfen farklı bir ad, kendi imza anahtarınızı ve kendi applicationId'nizi kullanın.

## Lisans

Telif hakkı © 2026 Project Gamers. [GNU GPL v3 veya üstü](LICENSE) (GPL-3.0-or-later). Programı değiştirip dağıtan herkes, değişen kaynak kodunu da aynı lisansla paylaşmak zorundadır.

## Yeni dil eklemek

`app/src/main/res/values-<kod>/strings.xml` dosyasını `values/strings.xml` ile aynı anahtarlarla oluştur, `LocaleHelper.java` içindeki `CODES` ve `NAMES` dizilerine dili ekle.
