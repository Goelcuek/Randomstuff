package com.goelcuek.sehrim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Brand = Color(0xFFC8102E)
private val DutyColor = Color(0xFFC8102E)
private val OpenColor = Color(0xFF2E7D32)
private val TR = Locale("tr")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Brand, onPrimary = Color.White, secondary = Color(0xFF1F2A44),
                    surface = Color(0xFFFFFFFF), background = Color(0xFFF6F4F0),
                    surfaceVariant = Color(0xFFF0ECE6), primaryContainer = Color(0xFFFBE3E6), onPrimaryContainer = Color(0xFF5B0614),
                )
            ) { App() }
        }
    }
}

fun norm(s: String): String = Normalizer.normalize(
    s.replace('İ', 'i').replace('I', 'ı').lowercase(TR).replace('ı', 'i'), Normalizer.Form.NFD,
).replace(Regex("\\p{Mn}+"), "")

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) vm.requestLocation()
    }
    val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun askLocation(onCity: ((City) -> Unit)? = null) {
        if (hasPerm) vm.requestLocation(onCity)
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (hasPerm) vm.requestLocation() }

    val city = vm.city
    if (city == null) {
        CityPicker(vm.locating, onPick = vm::selectCity, onLocate = { askLocation { c -> vm.selectCity(c) } })
        return
    }
    MainScreen(vm, city, hasPerm, ::askLocation)
}

// ------------------------------------------------------------------ Şehir seçimi

@Composable
fun CityPicker(locating: Boolean, onPick: (City) -> Unit, onLocate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Şehrim", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Brand)
        Spacer(Modifier.height(6.dp))
        Text("Otobüs, metro, tren hatları ve şu an açık eczaneler — tek yerde.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Text("Hangi şehirde yaşıyorsun?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        City.entries.forEach { c ->
            Surface(
                onClick = { onPick(c) }, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LocationCity, null, tint = Brand)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(c.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.MyLocation, null)
            Spacer(Modifier.width(8.dp))
            Text("Konumumdan bul")
        }
    }
}

// ------------------------------------------------------------------ Ana ekran

private enum class Tab(val label: String, val icon: ImageVector) {
    PHARMACY("Eczaneler", Icons.Default.LocalPharmacy),
    TRANSIT("Ulaşım", Icons.Default.DirectionsBus),
    MAP("Harita", Icons.Default.Map),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, city: City, hasPerm: Boolean, askLocation: (((City) -> Unit)?) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.PHARMACY) }
    var openLine by remember { mutableStateOf<Line?>(null) }
    var showCityDialog by remember { mutableStateOf(false) }

    openLine?.let { line ->
        BackHandler { openLine = null }
        LineDetail(vm, city, line, onBack = { openLine = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Şehrim", fontWeight = FontWeight.Black, color = Brand)
                        Spacer(Modifier.width(10.dp))
                        AssistChip(
                            onClick = { showCityDialog = true },
                            label = { Text(city.title) },
                            leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp)) },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { askLocation(null) }) {
                        if (vm.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(if (vm.location != null) Icons.Default.MyLocation else Icons.Default.LocationSearching, "Konum")
                    }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Yenile") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.PHARMACY -> PharmacyTab(vm, hasPerm) { askLocation(null) }
                Tab.TRANSIT -> TransitTab(vm, hasPerm, onLocate = { askLocation(null) }, onOpen = { openLine = it })
                Tab.MAP -> MapTab(vm, city)
            }
        }
    }

    if (showCityDialog) {
        AlertDialog(
            onDismissRequest = { showCityDialog = false },
            title = { Text("Şehir seç") },
            text = {
                Column {
                    City.entries.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { vm.selectCity(c); showCityDialog = false }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == city, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCityDialog = false; askLocation { c -> vm.selectCity(c) } }) { Text("Konumumdan bul") }
            },
            dismissButton = { TextButton(onClick = { showCityDialog = false }) { Text("Kapat") } },
        )
    }
}

// ------------------------------------------------------------------ Eczaneler

@Composable
fun PharmacyTab(vm: AppViewModel, hasPerm: Boolean, onLocate: () -> Unit) {
    val ctx = LocalContext.current
    var dutyOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val tick = vm.tick
    val now = remember(tick) { Hours.now() }
    val regularOpen = Hours.regularHoursNow(now)
    val list = remember(vm.duty, vm.static, vm.location, tick, dutyOnly) { vm.openPharmacies(includeRegular = !dutyOnly) }
    val q = norm(query.trim())
    val shown = remember(list, q) { if (q.isEmpty()) list else list.filter { norm(it.name + " " + it.address).contains(q) } }

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, TR).replaceFirstChar { it.titlecase(TR) }
            val holiday = Hours.isHoliday(now.toLocalDate())
            StatusCard(
                title = "${now.format(DateTimeFormatter.ofPattern("HH:mm"))} · $day${if (holiday) " (resmi tatil)" else ""}",
                body = if (regularOpen) "Normal çalışma saatleri: eczanelerin çoğu açık. Nöbetçiler ayrıca işaretli."
                else "Şu an normal eczaneler kapalı. Açık olanlar nöbetçi eczaneler (sabah ${vm.city?.dutyChange ?: "08:30"}'a kadar).",
                good = regularOpen,
            )
        }
        if (vm.location == null) item {
            Surface(
                onClick = onLocate, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, null, tint = Brand)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (vm.locating) "Konum alınıyor…" else if (hasPerm) "Konumu al — en yakın eczaneler üstte görünsün"
                        else "Konum izni ver — en yakın eczaneler üstte görünsün",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !dutyOnly, onClick = { dutyOnly = false }, label = { Text("Şu an açık") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = dutyOnly, onClick = { dutyOnly = true }, label = { Text("Yalnız nöbetçiler") })
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Eczane, mahalle veya ilçe ara") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (vm.loadingDuty && vm.duty == null) item { LoadingRow("Nöbetçi eczaneler yükleniyor…") }
        vm.dutyError?.let { e -> item { ErrorRow(e) } }
        if (!dutyOnly && vm.loadingStatic && vm.static == null) item { LoadingRow("Eczane haritası yükleniyor…") }
        vm.duty?.let { d ->
            if (d.dutyDate != Hours.currentDutyDate(vm.city ?: City.ISTANBUL, now).toString()) item {
                ErrorRow("Kaynaktaki nöbet listesi ${d.dutyDate} tarihli; bugünün listesi henüz yayımlanmamış olabilir.")
            }
        }
        item {
            Text(
                "${shown.size} eczane" + if (vm.location != null) " · yakından uzağa" else "",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(shown.take(300)) { p -> PharmacyCard(p) }
        if (shown.size > 300) item { Text("İlk 300 gösteriliyor — aramayı daraltın.", style = MaterialTheme.typography.bodySmall) }
        item {
            val d = vm.duty
            Text(
                buildString {
                    if (d != null) append("Nöbet listesi: ${d.source}${if (d.info.isNotBlank()) " — ${d.info}" else ""}\n")
                    append("Eczane konumları ve saatleri: © OpenStreetMap katkıcıları. Saati bilinmeyen eczaneler için olağan saatler (Pzt–Cmt 08:30–19:00) varsayılır; gitmeden önce aramanız önerilir.")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusCard(title: String, body: String, good: Boolean) {
    Surface(shape = RoundedCornerShape(18.dp), color = if (good) Color(0xFFE6F2E7) else Color(0xFFFFF1E0), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Default.Storefront else Icons.Default.NightsStay, null, tint = if (good) OpenColor else Color(0xFFB26A00))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp)); Text(text)
    }
}

@Composable
fun ErrorRow(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFEBEE), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = DutyColor)
            Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PharmacyCard(p: OpenPharmacy) {
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (p.onDuty) DutyColor else OpenColor), contentAlignment = Alignment.Center) {
                    Text("E", color = Color.White, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (p.onDuty) "NÖBETÇİ" else "AÇIK", color = if (p.onDuty) DutyColor else OpenColor,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (p.distanceM != null) Text(Geo.fmt(p.distanceM), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            if (p.address.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(p.address, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(p.hoursNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.phone.isNotBlank()) FilledTonalButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.phone}")))
                }) { Icon(Icons.Default.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ara") }
                if (p.lat != null && p.lon != null) Button(onClick = {
                    // Konumu haritaya ver; ulaşım şeklini (yürüme/araç/toplu taşıma) kullanıcı seçsin
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${p.lat},${p.lon}(${Uri.encode(p.name)})"))
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}"))
                    runCatching { ctx.startActivity(geo) }.onFailure { runCatching { ctx.startActivity(web) } }
                }) { Icon(Icons.Default.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Haritada aç") }
            }
        }
    }
}

// ------------------------------------------------------------------ Ulaşım

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitTab(vm: AppViewModel, hasPerm: Boolean, onLocate: () -> Unit, onOpen: (Line) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val lines = vm.lines
    val modes = remember(lines) { lines.map { it.mode }.distinct() }
    val q = norm(query.trim())
    val filtered = remember(lines, q, mode) {
        lines.filter { l ->
            (mode == null || l.mode == mode) && (q.isEmpty() || norm(l.ref).startsWith(q) || l.search.contains(q))
        }
    }
    val nearby = remember(lines, vm.location) { vm.nearbyStops() }

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Hat no, güzergâh veya durak ara") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == null, onClick = { mode = null }, label = { Text("Tümü (${lines.size})") })
                modes.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { mode = if (mode == m) null else m },
                        label = { Text("${Modes.label(m)} (${lines.count { it.mode == m }})") },
                        leadingIcon = { Icon(modeIcon(m), null, Modifier.size(16.dp)) })
                }
            }
        }
        if (vm.loadingStatic && lines.isEmpty()) item { LoadingRow("Hatlar yükleniyor… (ilk açılışta biraz sürebilir)") }
        vm.staticError?.let { e -> item { ErrorRow(e) } }

        if (q.isEmpty() && mode == null && lines.isNotEmpty()) {
            item { SectionTitle("Yakınımdaki duraklar") }
            if (vm.location == null) item {
                TextButton(onClick = onLocate) {
                    Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(6.dp))
                    Text(if (hasPerm) "Konumu al" else "Konum izni ver")
                }
            } else if (nearby.isEmpty()) item {
                Text("600 m içinde kayıtlı durak bulunamadı.", style = MaterialTheme.typography.bodyMedium)
            } else items(nearby) { (stop, dist, ls) ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Signpost, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stop.name.ifBlank { "İsimsiz durak" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(Geo.fmt(dist), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ls.sortedWith(compareBy({ Modes.order.indexOf(it.mode) }, { it.ref })).take(24).forEach { l ->
                                LineBadge(l, Modifier.clickable { onOpen(l) })
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Tüm hatlar") }
        }
        items(filtered.take(500)) { l -> LineRow(l) { onOpen(l) } }
        if (filtered.size > 500) item { Text("İlk 500 hat gösteriliyor — aramayı daraltın.", style = MaterialTheme.typography.bodySmall) }
        if (lines.isNotEmpty() && filtered.isEmpty()) item { Text("Eşleşen hat yok.") }
        vm.static?.let { s ->
            item {
                Text(
                    "Hat ve durak verisi: © OpenStreetMap katkıcıları (${s.updated}). Topluluk verisidir; bazı hatlar eksik olabilir. Sefer saatleri için ilgili işletmenin (İETT, EGO, Eskişehir Büyükşehir) uygulamasına bakın.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

fun lineColor(l: Line): Color {
    val c = l.variants.firstNotNullOfOrNull { r -> r.colour.takeIf { it.isNotBlank() } }
    return c?.let { runCatching { Color(android.graphics.Color.parseColor(if (it.startsWith("#")) it else "#$it")) }.getOrNull() }
        ?: Color(Modes.defaultColor(l.mode))
}

fun modeIcon(m: String): ImageVector = when (m) {
    "subway" -> Icons.Default.Subway
    "tram", "light_rail" -> Icons.Default.Tram
    "train", "monorail" -> Icons.Default.Train
    "ferry" -> Icons.Default.DirectionsBoat
    "funicular", "aerialway" -> Icons.Default.Landscape
    "minibus", "share_taxi" -> Icons.Default.AirportShuttle
    else -> Icons.Default.DirectionsBus
}

@Composable
fun LineBadge(l: Line, modifier: Modifier = Modifier) {
    val c = lineColor(l)
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(modeIcon(l.mode), null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(l.title.take(14), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun LineRow(l: Line, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 64.dp)) { LineBadge(l) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.subtitle.ifBlank { l.variants.first().name }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val v = l.variants.first()
                Text(
                    listOf(Modes.label(l.mode), if (v.stops.isNotEmpty()) "${v.stops.size} durak" else "", v.operator).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Hat detayı

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineDetail(vm: AppViewModel, city: City, line: Line, onBack: () -> Unit) {
    var vi by remember(line) { mutableIntStateOf(0) }
    val r = line.variants[vi.coerceIn(line.variants.indices)]
    val color = lineColor(line)
    val loc = vm.location
    val geom = remember(r) { r.geometry.map(Geo::decode) }
    val nearestIdx = remember(r, loc) {
        if (loc == null || r.stops.isEmpty()) -1
        else r.stops.indices.minBy { Geo.distance(loc.latitude, loc.longitude, r.stops[it].lat, r.stops[it].lon) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(line); Spacer(Modifier.width(10.dp))
                        Text(Modes.label(line.mode), style = MaterialTheme.typography.titleMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OsmMap(
                Modifier.fillMaxWidth().height(300.dp), city.lat, city.lon,
                pins = r.stops.mapIndexed { i, s ->
                    MapPin(s.lat, s.lon, s.name.ifBlank { "Durak ${i + 1}" }, "${i + 1}. durak",
                        color = if (i == nearestIdx) Color(0xFF1E88E5).toArgb() else color.toArgb(), sizeDp = if (i == 0 || i == r.stops.lastIndex) 16 else 11)
                },
                lines = geom.map { MapLine(it, color.copy(alpha = 0.85f).toArgb(), 12f) },
                me = loc?.let { it.latitude to it.longitude },
                fitKey = r.id,
            )
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item {
                    Text(r.name.ifBlank { line.subtitle }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (r.operator.isNotBlank()) Text(r.operator, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                if (line.variants.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.variants.forEachIndexed { i, v ->
                            FilterChip(selected = i == vi, onClick = { vi = i }, label = {
                                Text(if (v.to.isNotBlank()) "→ ${v.to}" else v.name.ifBlank { "Yön ${i + 1}" }, maxLines = 1)
                            })
                        }
                    }
                }
                if (nearestIdx >= 0 && loc != null) item {
                    val s = r.stops[nearestIdx]
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFE3F2FD), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(
                            "Size en yakın durak: ${s.name.ifBlank { "${nearestIdx + 1}. durak" }} · ${Geo.fmt(Geo.distance(loc.latitude, loc.longitude, s.lat, s.lon))}",
                            modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (r.stops.isEmpty()) item { Text("Bu hat için durak listesi OpenStreetMap'te yok; güzergâh haritada gösteriliyor.") }
                itemsIndexed(r.stops) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(color.copy(alpha = if (i == 0 || i == r.stops.lastIndex) 0.4f else 1f)))
                            Box(Modifier.size(if (i == 0 || i == r.stops.lastIndex) 16.dp else 12.dp).clip(CircleShape)
                                .background(if (i == nearestIdx) Color(0xFF1E88E5) else Color.White)
                                .padding(3.dp).clip(CircleShape).background(color))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.name.ifBlank { "Durak ${i + 1}" },
                            modifier = Modifier.padding(vertical = 10.dp).weight(1f),
                            fontWeight = if (i == 0 || i == r.stops.lastIndex || i == nearestIdx) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Harita

@Composable
fun MapTab(vm: AppViewModel, city: City) {
    val list = remember(vm.duty, vm.static, vm.location, vm.tick) { vm.openPharmacies(includeRegular = true) }
    var dutyOnly by rememberSaveable { mutableStateOf(false) }
    val pins = remember(list, dutyOnly) {
        list.filter { it.lat != null && (!dutyOnly || it.onDuty) }.map {
            MapPin(it.lat!!, it.lon!!, it.name, listOf(if (it.onDuty) "Nöbetçi" else "Açık", Geo.fmt(it.distanceM), it.address).filter(String::isNotBlank).joinToString(" · "),
                color = (if (it.onDuty) DutyColor else OpenColor).toArgb(), sizeDp = if (it.onDuty) 16 else 11)
        }
    }
    val loc = vm.location
    Box(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxSize(), loc?.latitude ?: city.lat, loc?.longitude ?: city.lon, pins = pins,
            me = loc?.let { it.latitude to it.longitude }, fitKey = "${city.slug}|${loc != null}", fitToContent = false,
        )
        Row(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !dutyOnly, onClick = { dutyOnly = false }, label = { Text("Şu an açık (${list.size})") },
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White))
            FilterChip(selected = dutyOnly, onClick = { dutyOnly = true }, label = { Text("Nöbetçiler (${list.count { it.onDuty }})") },
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White))
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.92f)) {
            Column(Modifier.padding(10.dp)) {
                Legend(DutyColor, "Nöbetçi eczane"); Legend(OpenColor, "Açık eczane"); Legend(Color(0xFF1E88E5), "Konumunuz")
                Text("© OpenStreetMap", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp)); Text(t, fontSize = 12.sp)
    }
}
