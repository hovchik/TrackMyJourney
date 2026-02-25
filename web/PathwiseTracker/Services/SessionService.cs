using System.Collections.Concurrent;
using System.Text.Json;
using PathwiseTracker.Models;

namespace PathwiseTracker.Services;

public sealed class SessionService
{
    private readonly ConcurrentDictionary<string, TrackingSession> _sessions = new();
    private readonly string _dataDir;
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public SessionService(IWebHostEnvironment env)
    {
        _dataDir = Path.Combine(env.ContentRootPath, "sessions");
        Directory.CreateDirectory(_dataDir);
    }

    /// <summary>Create a new session tied to a SignalR connection.</summary>
    public TrackingSession CreateSession(string connectionId, string webhookKey)
    {
        var session = new TrackingSession { WebhookKey = webhookKey };
        _sessions[connectionId] = session;
        return session;
    }

    /// <summary>Find the session for a SignalR connection.</summary>
    public TrackingSession? GetByConnection(string connectionId)
    {
        _sessions.TryGetValue(connectionId, out var s);
        return s;
    }

    /// <summary>Find all connections listening for a given webhook key.</summary>
    public IEnumerable<(string ConnectionId, TrackingSession Session)> GetByWebhookKey(string key)
    {
        foreach (var kvp in _sessions)
        {
            if (string.Equals(kvp.Value.WebhookKey, key, StringComparison.Ordinal))
                yield return (kvp.Key, kvp.Value);
        }
    }

    /// <summary>End the session, persist to JSON, remove from memory.</summary>
    public string? EndSession(string connectionId)
    {
        if (!_sessions.TryRemove(connectionId, out var session))
            return null;

        session.EndedUtc = DateTime.UtcNow;
        var filePath = Path.Combine(_dataDir, $"{session.Id}.json");

        var export = new SessionExport
        {
            SessionId = session.Id,
            WebhookKey = session.WebhookKey,
            StartedUtc = session.StartedUtc,
            EndedUtc = session.EndedUtc,
            Points = session.Points.ToList()
        };

        File.WriteAllText(filePath, JsonSerializer.Serialize(export, JsonOpts));
        return session.Id;
    }

    /// <summary>Add a point to all sessions matching the webhook key.</summary>
    public List<string> AddPoint(TrackPointData point)
    {
        var connectionIds = new List<string>();
        foreach (var (connId, session) in GetByWebhookKey(point.Key))
        {
            session.Points.Add(point);
            connectionIds.Add(connId);
        }
        return connectionIds;
    }

    /// <summary>Get the file path for a completed session's JSON.</summary>
    public string? GetSessionFilePath(string sessionId)
    {
        var filePath = Path.Combine(_dataDir, $"{sessionId}.json");
        return File.Exists(filePath) ? filePath : null;
    }

    /// <summary>Get the current points for an active session by connection.</summary>
    public SessionExport? GetActiveSessionExport(string connectionId)
    {
        if (!_sessions.TryGetValue(connectionId, out var session))
            return null;

        return new SessionExport
        {
            SessionId = session.Id,
            WebhookKey = session.WebhookKey,
            StartedUtc = session.StartedUtc,
            EndedUtc = null,
            Points = session.Points.ToList()
        };
    }
}
