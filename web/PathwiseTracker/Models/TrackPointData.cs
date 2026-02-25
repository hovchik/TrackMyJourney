using System.Text.Json.Serialization;

namespace PathwiseTracker.Models;

public sealed class TrackPointData
{
    [JsonPropertyName("key")]
    public string Key { get; set; } = "";

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("lat")]
    public double Lat { get; set; }

    [JsonPropertyName("lng")]
    public double Lng { get; set; }

    [JsonPropertyName("alt")]
    public double? Alt { get; set; }

    [JsonPropertyName("speed_kmh")]
    public double? SpeedKmh { get; set; }

    [JsonPropertyName("bearing")]
    public double? Bearing { get; set; }

    [JsonPropertyName("accuracy_m")]
    public double? AccuracyM { get; set; }

    [JsonPropertyName("activity")]
    public string? Activity { get; set; }

    [JsonPropertyName("heart_rate")]
    public int? HeartRate { get; set; }

    [JsonPropertyName("battery")]
    public int? Battery { get; set; }
}

public sealed class TrackingSession
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N")[..12];
    public string WebhookKey { get; set; } = "";
    public DateTime StartedUtc { get; set; } = DateTime.UtcNow;
    public DateTime? EndedUtc { get; set; }
    public List<TrackPointData> Points { get; } = [];
}

public sealed class SessionExport
{
    [JsonPropertyName("session_id")]
    public string SessionId { get; set; } = "";

    [JsonPropertyName("webhook_key")]
    public string WebhookKey { get; set; } = "";

    [JsonPropertyName("started_utc")]
    public DateTime StartedUtc { get; set; }

    [JsonPropertyName("ended_utc")]
    public DateTime? EndedUtc { get; set; }

    [JsonPropertyName("points")]
    public List<TrackPointData> Points { get; set; } = [];
}
