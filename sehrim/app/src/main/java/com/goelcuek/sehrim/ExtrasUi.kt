package com.goelcuek.sehrim

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Red = Color(0xFFC8102E)
private val Green = Color(0xFF2E7D32)
private val Blue = Color(0xFF1565C0)

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun openInMaps(ctx: android.content.Context, lat: Double, lon: Double, name: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(name)})"))
    runCatching { ctx.startActivity(geo) }.onFailure { openUrl(ctx, "https://www.google.com/maps/search/?api=1&query=$lat,$lon") }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LocateHint(vm: AppViewModel, hasPerm: Boolean, onLocate: () -> Unit, what: String) {
    if (vm.location != null) return
    Surface(onClick = onLocate, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MyLocation, null, tint = Red)
            Spacer(Modifier.width(12.dp))
            Text(
                if (vm.locating) "Konum alınıyor…" else if (hasPerm) "Konumu al — $what" else "Konum izni ver — $what",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ------------------------------------------------------------------ Hastaneler

@Composable
fun HospitalTab(vm: AppViewModel, hasPerm: Boolean, onLocate: () -> Unit) {
    val ctx = LocalContext.current
    var emergencyOnly by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(vm.static, vm.location, emergencyOnly) { vm.hospitals(emergencyOnly) }
    val q = norm(query.trim())
    val list = remember(all, q) { if (q.isEmpty()) all else all.filter { norm(it.first.name + " " + it.first.address).contains(q) } }

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) },
                shape = RoundedCornerShape(18.dp), color = Red, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalHospital, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Acil durumda 112", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("Ambulans, itfaiye, polis — dokun ve ara", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.Call, null, tint = Color.White)
                }
            }
        }
        item { LocateHint(vm, hasPerm, onLocate, "en yakın hastaneler üstte görünsün") }
        item {
            Row {
                FilterChip(selected = emergencyOnly, onClick = { emergencyOnly = true }, label = { Text("Acil servisi olanlar") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !emergencyOnly, onClick = { emergencyOnly = false }, label = { Text("Tüm sağlık kuruluşları") })
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Hastane veya semt ara") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (vm.loadingStatic && vm.static == null) item { LoadingRow("Sağlık kuruluşları yükleniyor…") }
        vm.staticError?.let { item { ErrorRow(it) } }
        if (vm.static != null && vm.static!!.hospitals.isEmpty()) item {
            ErrorRow("Bu şehrin sağlık kuruluşu verisi henüz hazırlanıyor. Biraz sonra ↻ ile yenileyin.")
        }
        item {
            Text("${list.size} kuruluş" + if (vm.location != null) " · yakından uzağa" else "",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(list.take(200)) { (h, d) ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (h.kind == "hospital") Red else Blue), contentAlignment = Alignment.Center) {
                        Icon(if (h.kind == "hospital") Icons.Default.LocalHospital else Icons.Default.MedicalServices, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.kindLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (h.emergency == "yes") { Spacer(Modifier.width(6.dp)); Pill("ACİL", Red) }
                            else if (h.emergency == "likely") { Spacer(Modifier.width(6.dp)); Pill("ACİL (muhtemel)", Color(0xFFE57373)) }
                        }
                    }
                    if (d != null) Text(Geo.fmt(d), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                }
                if (h.address.isNotBlank()) {
                    Spacer(Modifier.height(6.dp)); Text(h.address, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (h.phone.isNotBlank()) FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${h.phone.filter { it.isDigit() || it == '+' }}")))
                    }) { Icon(Icons.Default.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ara") }
                    Button(onClick = { openInMaps(ctx, h.lat, h.lon, h.name) }) {
                        Icon(Icons.Default.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Haritada aç")
                    }
                }
            }
        }
        item {
            Text("Kaynak: © OpenStreetMap katkıcıları. \"Acil (muhtemel)\": devlet hastanesi, acil servis bilgisi kayıtlı değil. Hayati durumda 112'yi arayın.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Depremler

private fun quakeColor(m: Double) = when {
    m >= 5 -> Color(0xFFB71C1C)
    m >= 4 -> Color(0xFFE53935)
    m >= 3 -> Color(0xFFFB8C00)
    m >= 2 -> Color(0xFFFDD835)
    else -> Color(0xFF9E9E9E)
}

fun ago(t: LocalDateTime): String {
    val d = Duration.between(t, Hours.now())
    return when {
        d.toMinutes() < 1 -> "az önce"
        d.toMinutes() < 60 -> "${d.toMinutes()} dk önce"
        d.toHours() < 24 -> "${d.toHours()} sa önce"
        else -> "${d.toDays()} gün önce"
    }
}

@Composable
fun QuakeTab(vm: AppViewModel, city: City) {
    LaunchedEffect(Unit) { vm.loadQuakes() }
    var radiusKm by rememberSaveable { mutableIntStateOf(200) }
    var minMag by rememberSaveable { mutableStateOf(2.0) }
    val loc = vm.location
    val refLat = loc?.latitude ?: city.lat
    val refLon = loc?.longitude ?: city.lon
    val list = remember(vm.quakes, radiusKm, minMag, refLat, refLon) {
        vm.quakes?.items.orEmpty().map { it to Geo.distance(refLat, refLon, it.lat, it.lon) }
            .filter { (q, d) -> q.magnitude >= minMag && (radiusKm == 0 || d <= radiusKm * 1000) }
    }
    val tf = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Son 7 gün · ${if (loc != null) "konumunuza" else city.title + " merkezine"} göre",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 500, 0).forEach { r ->
                    FilterChip(selected = radiusKm == r, onClick = { radiusKm = r }, label = { Text(if (r == 0) "Tüm Türkiye" else "$r km") })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 2.0, 3.0, 4.0).forEach { m ->
                    FilterChip(selected = minMag == m, onClick = { minMag = m }, label = { Text("≥ ${m.toInt()}.0") })
                }
            }
        }
        if (vm.loadingQuakes && vm.quakes == null) item { LoadingRow("Depremler yükleniyor…") }
        vm.quakeError?.let { item { ErrorRow(it) } }
        if (vm.quakes != null && list.isEmpty()) item {
            Text("Bu filtrelere uyan deprem yok.", modifier = Modifier.padding(8.dp))
        }
        items(list.take(300)) { (q, d) ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(quakeColor(q.magnitude)), contentAlignment = Alignment.Center) {
                        Text(String.format(java.util.Locale.US, "%.1f", q.magnitude), fontWeight = FontWeight.Black, fontSize = 18.sp,
                            color = if (q.magnitude in 2.0..2.99) Color.Black else Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.place, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${ago(q.time)} · ${q.time.format(tf)} · derinlik ${String.format(java.util.Locale.US, "%.1f", q.depthKm)} km",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Geo.fmt(d), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Text("Kaynak: ${vm.quakes?.source ?: "AFAD"} (büyüklük ≥ 1.0). Veriler ön değerlendirmedir, güncellenebilir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Kesintiler

@Composable
fun OutageTab(vm: AppViewModel, city: City) {
    val ctx = LocalContext.current
    LaunchedEffect(city) { vm.loadOutages() }
    var query by rememberSaveable { mutableStateOf("") }
    val q = norm(query.trim())
    val now = Hours.now()
    val list = remember(vm.outages, q) {
        vm.outages.filter { q.isEmpty() || norm("${it.district} ${it.places} ${it.detail}").contains(q) }
            .sortedWith(compareBy({ it.end != null && it.end < now }, { it.start }))
    }
    val tf = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (city == City.ANKARA) {
            item { SectionTitle("Su kesintileri · ASKİ") }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("İlçe veya mahalle ara") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (vm.loadingOutages) item { LoadingRow("Kesintiler yükleniyor…") }
            vm.outageError?.let { item { ErrorRow(it) } }
            if (!vm.loadingOutages && vm.outageError == null && list.isEmpty()) item {
                Text("Şu an listelenmiş su kesintisi yok.", modifier = Modifier.padding(8.dp))
            }
            items(list) { o ->
                var expanded by remember { mutableStateOf(false) }
                val active = (o.start == null || o.start <= now) && (o.end == null || o.end >= now)
                val past = o.end != null && o.end < now
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WaterDrop, null, tint = Blue)
                        Spacer(Modifier.width(8.dp))
                        Text(o.district, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        when {
                            active -> Pill("ŞU AN", Red)
                            past -> Pill("BİTTİ", Color.Gray)
                            else -> Pill("YAKLAŞAN", Color(0xFFFB8C00))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(o.kind.ifBlank { null }, o.start?.format(tf)?.let { s -> "$s → ${o.end?.format(tf) ?: "?"}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (o.places.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Etkilenen: ${o.places}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    if (o.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(o.detail, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 50 else 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (expanded) "Daha az" else "Ayrıntı")
                        }
                    }
                }
            }
        }
        item { SectionTitle(if (city == City.ANKARA) "Diğer kaynaklar" else "Resmi kesinti sayfaları") }
        if (city != City.ANKARA) item {
            Text(
                "${city.title} için su ve elektrik dağıtım şirketleri kesintileri adres sorgusuyla veya doğrulama (captcha) ile gösteriyor; uygulama içinden listelenemiyor. Aşağıdan resmi sayfayı açabilirsiniz.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(OutageSources.links(city)) { l ->
            Surface(onClick = { openUrl(ctx, l.url) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (l.utility == "Su") Icons.Default.WaterDrop else Icons.Default.Bolt, null,
                        tint = if (l.utility == "Su") Blue else Color(0xFFF9A825))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.title, fontWeight = FontWeight.SemiBold)
                        Text(l.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Elektrik arızası için 186'yı arayabilirsiniz.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Tam ekran harita

@Composable
fun FullMap(vm: AppViewModel, city: City) {
    val list = remember(vm.duty, vm.static, vm.location, vm.tick) { vm.openPharmacies(includeRegular = true) }
    var showPharm by rememberSaveable { mutableStateOf(true) }
    var showHosp by rememberSaveable { mutableStateOf(true) }
    val pins = remember(list, vm.static, showPharm, showHosp) {
        buildList {
            if (showHosp) vm.static?.hospitals?.filter { it.hasEmergency }?.forEach {
                add(MapPin(it.lat, it.lon, it.name, it.kindLabel, color = Blue.toArgb(), sizeDp = 15))
            }
            if (showPharm) list.filter { it.lat != null }.forEach {
                add(MapPin(it.lat!!, it.lon!!, it.name,
                    listOf(if (it.onDuty) "Nöbetçi" else "Açık", Geo.fmt(it.distanceM), it.address).filter(String::isNotBlank).joinToString(" · "),
                    color = (if (it.onDuty) Red else Green).toArgb(), sizeDp = if (it.onDuty) 16 else 11))
            }
        }
    }
    val loc = vm.location
    Box(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxSize(), loc?.latitude ?: city.lat, loc?.longitude ?: city.lon, pins = pins,
            me = loc?.let { it.latitude to it.longitude }, fitKey = "${city.slug}|${loc != null}", fitToContent = false,
        )
        Row(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showPharm, onClick = { showPharm = !showPharm }, label = { Text("Açık eczaneler") },
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White))
            FilterChip(selected = showHosp, onClick = { showHosp = !showHosp }, label = { Text("Acil servisler") },
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White))
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.92f)) {
            Column(Modifier.padding(10.dp)) {
                Legend(Red, "Nöbetçi eczane"); Legend(Green, "Açık eczane"); Legend(Blue, "Hastane (acil)")
                Legend(Color(0xFF1E88E5), "Konumunuz")
                Text("© OpenStreetMap", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}
