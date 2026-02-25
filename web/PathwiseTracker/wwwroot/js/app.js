// ═══════════════════════════════════════════════════════
//  PathwiseTracker — Client
// ═══════════════════════════════════════════════════════

const TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
const TILE_ATTR = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';

// ── State ────────────────────────────────────────────
let connection = null;
let sessionId = null;
let liveMap = null;
let livePolyline = null;
let liveMarker = null;
let livePoints = [];
let explorerMap = null;
let explorerPolyline = null;
let explorerMarkers = [];
let explorerData = null;

// ── DOM refs ─────────────────────────────────────────
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// ── Tab switching ────────────────────────────────────
$$('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        $$('.tab-btn').forEach(b => b.classList.remove('active'));
        $$('.tab-content').forEach(t => t.classList.remove('active'));
        btn.classList.add('active');
        $(`#tab-${btn.dataset.tab}`).classList.add('active');

        // Invalidate map sizes after tab switch
        setTimeout(() => {
            liveMap?.invalidateSize();
            explorerMap?.invalidateSize();
        }, 50);
    });
});

// ══════════════════════════════════════════════════════
//  LIVE TAB
// ══════════════════════════════════════════════════════

// ── Init live map ────────────────────────────────────
function initLiveMap() {
    liveMap = L.map('map').setView([40.7128, -74.006], 13);
    L.tileLayer(TILE_URL, { attribution: TILE_ATTR, maxZoom: 19 }).addTo(liveMap);
    livePolyline = L.polyline([], { color: '#6c8cff', weight: 3, opacity: .85 }).addTo(liveMap);
}

// ── Connect ──────────────────────────────────────────
$('#btn-connect').addEventListener('click', async () => {
    const key = $('#webhook-key').value.trim();
    if (!key) return;

    $('#btn-connect').disabled = true;
    $('#btn-connect').textContent = 'Connecting...';

    try {
        connection = new signalR.HubConnectionBuilder()
            .withUrl('/hubs/tracking')
            .withAutomaticReconnect()
            .build();

        connection.on('SessionStarted', (id, whKey) => {
            sessionId = id;
            livePoints = [];
            updateSessionUI(id, whKey);
            setConnected(true);
        });

        connection.on('NewPoint', (point) => {
            livePoints.push(point);
            addPointToMap(point);
            addPointToLog(point);
            updateStats();
        });

        connection.on('DownloadReady', (exportData) => {
            downloadJson(exportData, `session-${sessionId}.json`);
        });

        connection.onclose(() => {
            setConnected(false);
            sessionId = null;
        });

        connection.onreconnected(() => {
            // Re-start session with same key after reconnect
            const k = $('#webhook-key').value.trim();
            if (k) connection.invoke('StartSession', k);
        });

        await connection.start();
        await connection.invoke('StartSession', key);
    } catch (err) {
        console.error('Connection failed:', err);
        $('#btn-connect').disabled = false;
        $('#btn-connect').textContent = 'Connect';
    }
});

// ── Disconnect ───────────────────────────────────────
$('#btn-disconnect').addEventListener('click', async () => {
    if (connection) {
        await connection.stop();
        connection = null;
    }
    setConnected(false);
});

// ── Download current session ─────────────────────────
$('#btn-download').addEventListener('click', () => {
    if (connection && sessionId) {
        connection.invoke('RequestDownload');
    }
});

// ── Clear map ────────────────────────────────────────
$('#btn-clear').addEventListener('click', () => {
    livePoints = [];
    livePolyline.setLatLngs([]);
    if (liveMarker) { liveMap.removeLayer(liveMarker); liveMarker = null; }
    $('.point-log').innerHTML = '<h3>Point Log</h3>';
    updateStats();
});

// ── Map helpers ──────────────────────────────────────
function addPointToMap(pt) {
    const latlng = [pt.lat, pt.lng];
    livePolyline.addLatLng(latlng);

    if (liveMarker) {
        liveMarker.setLatLng(latlng);
    } else {
        liveMarker = L.circleMarker(latlng, {
            radius: 7, fillColor: '#6c8cff', fillOpacity: 1,
            color: '#fff', weight: 2
        }).addTo(liveMap);
    }

    liveMap.panTo(latlng);
}

function addPointToLog(pt) {
    const log = $('.point-log');
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    const time = new Date(pt.timestamp).toLocaleTimeString();
    entry.innerHTML = `
        <span class="coords">${pt.lat.toFixed(5)}, ${pt.lng.toFixed(5)}</span>
        ${pt.alt != null ? ` &middot; ${pt.alt.toFixed(0)}m` : ''}
        ${pt.activity ? ` <span class="activity-tag">${pt.activity}</span>` : ''}
        ${pt.speed_kmh != null ? ` &middot; ${pt.speed_kmh.toFixed(1)} km/h` : ''}
        <br>${time}
    `;
    // Insert after the h3
    log.appendChild(entry);
    entry.scrollIntoView({ behavior: 'smooth', block: 'end' });
}

function updateStats() {
    const n = livePoints.length;
    $('#stat-points').textContent = n;

    if (n > 0) {
        const last = livePoints[n - 1];
        $('#stat-speed').textContent = last.speed_kmh != null ? last.speed_kmh.toFixed(1) : '--';
        $('#stat-alt').textContent = last.alt != null ? last.alt.toFixed(0) : '--';
        $('#stat-activity').textContent = last.activity || '--';
    } else {
        $('#stat-speed').textContent = '--';
        $('#stat-alt').textContent = '--';
        $('#stat-activity').textContent = '--';
    }
}

function updateSessionUI(id, key) {
    $('#session-id').textContent = id;
    $('#session-key').textContent = key;
    $('#session-started').textContent = new Date().toLocaleTimeString();
}

function setConnected(connected) {
    const dot = $('.status-dot');
    const text = $('.status-text');
    const connectBtn = $('#btn-connect');
    const disconnectBtn = $('#btn-disconnect');
    const info = $('.session-info');

    if (connected) {
        dot.classList.add('connected');
        text.textContent = 'Live';
        connectBtn.style.display = 'none';
        disconnectBtn.style.display = 'inline-block';
        info.style.display = 'block';
        $('#webhook-key').disabled = true;
    } else {
        dot.classList.remove('connected');
        text.textContent = 'Disconnected';
        connectBtn.style.display = 'inline-block';
        connectBtn.disabled = false;
        connectBtn.textContent = 'Connect';
        disconnectBtn.style.display = 'none';
        info.style.display = 'none';
        $('#webhook-key').disabled = false;
    }
}

// ══════════════════════════════════════════════════════
//  EXPLORER TAB
// ══════════════════════════════════════════════════════

function initExplorerMap() {
    explorerMap = L.map('explorer-map').setView([40.7128, -74.006], 13);
    L.tileLayer(TILE_URL, { attribution: TILE_ATTR, maxZoom: 19 }).addTo(explorerMap);
}

// ── File import ──────────────────────────────────────
const dropZone = $('.drop-zone');
const fileInput = $('#file-input');

dropZone.addEventListener('click', () => fileInput.click());
dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); });
dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag-over'));
dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('drag-over');
    const file = e.dataTransfer.files[0];
    if (file) loadFile(file);
});

fileInput.addEventListener('change', () => {
    if (fileInput.files[0]) loadFile(fileInput.files[0]);
});

function loadFile(file) {
    const reader = new FileReader();
    reader.onload = (e) => {
        try {
            const data = JSON.parse(e.target.result);
            // Support both single session and array
            if (data.points) {
                explorerData = data;
                renderExplorerSession(data);
            } else {
                alert('Invalid session file. Expected JSON with "points" array.');
            }
        } catch {
            alert('Failed to parse JSON file.');
        }
    };
    reader.readAsText(file);
}

function renderExplorerSession(data) {
    // Show panels
    $('.loaded-session').classList.add('visible');
    $('.explorer-controls').classList.add('visible');

    // Meta
    $('#ex-session-id').textContent = data.session_id || '--';
    $('#ex-points').textContent = data.points.length;
    $('#ex-started').textContent = data.started_utc ? new Date(data.started_utc).toLocaleString() : '--';
    $('#ex-ended').textContent = data.ended_utc ? new Date(data.ended_utc).toLocaleString() : '--';

    // Duration
    if (data.started_utc && data.ended_utc) {
        const dur = (new Date(data.ended_utc) - new Date(data.started_utc)) / 1000;
        const m = Math.floor(dur / 60);
        const s = Math.floor(dur % 60);
        $('#ex-duration').textContent = `${m}m ${s}s`;
    } else {
        $('#ex-duration').textContent = '--';
    }

    // Distinct activities
    const acts = [...new Set(data.points.map(p => p.activity).filter(Boolean))];
    $('#ex-activities').textContent = acts.length > 0 ? acts.join(', ') : '--';

    renderExplorerMap(data.points);
    renderExplorerPointList(data.points);
}

function renderExplorerMap(points, colorBy = 'none') {
    // Clear previous
    if (explorerPolyline) explorerMap.removeLayer(explorerPolyline);
    explorerMarkers.forEach(m => explorerMap.removeLayer(m));
    explorerMarkers = [];

    if (points.length === 0) return;

    const latlngs = points.map(p => [p.lat, p.lng]);

    if (colorBy === 'activity') {
        // Draw segments colored by activity
        const actColors = {
            WALKING: '#4CAF50', RUNNING: '#FF9800', CYCLING: '#2196F3',
            DRIVING: '#9C27B0', FLYING: '#E91E63', STATIONARY: '#607D8B',
            HIKING: '#8BC34A', UNKNOWN: '#999'
        };
        for (let i = 1; i < points.length; i++) {
            const color = actColors[points[i].activity] || '#999';
            L.polyline([[points[i-1].lat, points[i-1].lng], [points[i].lat, points[i].lng]], {
                color, weight: 3, opacity: .85
            }).addTo(explorerMap);
        }
    } else if (colorBy === 'speed') {
        const speeds = points.map(p => p.speed_kmh || 0);
        const maxSpd = Math.max(...speeds, 1);
        for (let i = 1; i < points.length; i++) {
            const ratio = (points[i].speed_kmh || 0) / maxSpd;
            const color = speedToColor(ratio);
            L.polyline([[points[i-1].lat, points[i-1].lng], [points[i].lat, points[i].lng]], {
                color, weight: 3, opacity: .85
            }).addTo(explorerMap);
        }
    } else {
        explorerPolyline = L.polyline(latlngs, { color: '#6c8cff', weight: 3, opacity: .85 }).addTo(explorerMap);
    }

    // Start/end markers
    const startMarker = L.circleMarker(latlngs[0], {
        radius: 8, fillColor: '#44d7b6', fillOpacity: 1, color: '#fff', weight: 2
    }).bindTooltip('Start').addTo(explorerMap);
    explorerMarkers.push(startMarker);

    const endMarker = L.circleMarker(latlngs[latlngs.length - 1], {
        radius: 8, fillColor: '#ff6b6b', fillOpacity: 1, color: '#fff', weight: 2
    }).bindTooltip('End').addTo(explorerMap);
    explorerMarkers.push(endMarker);

    explorerMap.fitBounds(L.latLngBounds(latlngs).pad(0.1));
}

function speedToColor(ratio) {
    // Green (0) → Yellow (.5) → Red (1)
    const r = Math.round(ratio < 0.5 ? ratio * 2 * 255 : 255);
    const g = Math.round(ratio < 0.5 ? 255 : (1 - ratio) * 2 * 255);
    return `rgb(${r},${g},50)`;
}

function renderExplorerPointList(points) {
    const container = $('.explorer-points');
    container.innerHTML = '<h3>Points (' + points.length + ')</h3>';

    points.forEach((pt, i) => {
        const el = document.createElement('div');
        el.className = 'point-item';
        const time = new Date(pt.timestamp).toLocaleTimeString();
        el.innerHTML = `
            <span class="pt-time">#${i + 1} ${time}</span><br>
            <span class="pt-coords">${pt.lat.toFixed(5)}, ${pt.lng.toFixed(5)}</span>
            ${pt.alt != null ? ` &middot; ${pt.alt.toFixed(0)}m` : ''}
            ${pt.activity ? ` <span class="activity-tag">${pt.activity}</span>` : ''}
        `;
        el.addEventListener('click', () => {
            explorerMap.setView([pt.lat, pt.lng], 17);
        });
        container.appendChild(el);
    });
}

// ── Explorer controls ────────────────────────────────
$('#color-by').addEventListener('change', (e) => {
    if (explorerData) renderExplorerMap(explorerData.points, e.target.value);
});

$('#toggle-markers').addEventListener('change', (e) => {
    if (!explorerData) return;
    // Remove existing point markers (not start/end)
    explorerMarkers.filter(m => m._pointMarker).forEach(m => explorerMap.removeLayer(m));
    explorerMarkers = explorerMarkers.filter(m => !m._pointMarker);

    if (e.target.checked) {
        explorerData.points.forEach((pt, i) => {
            const m = L.circleMarker([pt.lat, pt.lng], {
                radius: 3, fillColor: '#6c8cff', fillOpacity: .6, color: 'transparent'
            }).bindTooltip(`#${i + 1}: ${pt.lat.toFixed(5)}, ${pt.lng.toFixed(5)}`).addTo(explorerMap);
            m._pointMarker = true;
            explorerMarkers.push(m);
        });
    }
});

$('#btn-export-csv').addEventListener('click', () => {
    if (!explorerData) return;
    const header = 'timestamp,lat,lng,alt,speed_kmh,bearing,accuracy_m,activity,heart_rate,battery\n';
    const rows = explorerData.points.map(p =>
        [p.timestamp, p.lat, p.lng, p.alt ?? '', p.speed_kmh ?? '', p.bearing ?? '',
         p.accuracy_m ?? '', p.activity ?? '', p.heart_rate ?? '', p.battery ?? ''].join(',')
    ).join('\n');
    downloadText(header + rows, `session-${explorerData.session_id || 'export'}.csv`, 'text/csv');
});

$('#btn-export-gpx').addEventListener('click', () => {
    if (!explorerData) return;
    const pts = explorerData.points.map(p => {
        const time = new Date(p.timestamp).toISOString();
        return `      <trkpt lat="${p.lat}" lon="${p.lng}">${p.alt != null ? `\n        <ele>${p.alt}</ele>` : ''}\n        <time>${time}</time>\n      </trkpt>`;
    }).join('\n');
    const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="PathwiseTracker">
  <trk>
    <name>Session ${explorerData.session_id || ''}</name>
    <trkseg>
${pts}
    </trkseg>
  </trk>
</gpx>`;
    downloadText(gpx, `session-${explorerData.session_id || 'export'}.gpx`, 'application/gpx+xml');
});

// ── Utilities ────────────────────────────────────────
function downloadJson(data, filename) {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    triggerDownload(blob, filename);
}

function downloadText(text, filename, mime) {
    const blob = new Blob([text], { type: mime });
    triggerDownload(blob, filename);
}

function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

// ── Init ─────────────────────────────────────────────
initLiveMap();
initExplorerMap();
setConnected(false);
