using Microsoft.AspNetCore.SignalR;
using PathwiseTracker.Hubs;
using PathwiseTracker.Models;
using PathwiseTracker.Services;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddSignalR();
builder.Services.AddSingleton<SessionService>();

var app = builder.Build();
app.UseDefaultFiles();
app.UseStaticFiles();

// ── Webhook endpoint (called by TrackMyJourney app) ──────────────
app.MapPost("/api/hook", async (
    TrackPointData point,
    SessionService sessions,
    IHubContext<TrackingHub> hub) =>
{
    if (string.IsNullOrWhiteSpace(point.Key))
        return Results.BadRequest(new { error = "Missing key" });

    var connectionIds = sessions.AddPoint(point);
    if (connectionIds.Count == 0)
        return Results.Ok(new { status = "no_active_session" });

    // Push to every browser tab listening for this key
    foreach (var connId in connectionIds)
    {
        await hub.Clients.Client(connId).SendAsync("NewPoint", point);
    }

    return Results.Ok(new { status = "delivered", listeners = connectionIds.Count });
});

// ── Download completed session JSON ──────────────────────────────
app.MapGet("/api/session/{sessionId}/download", (string sessionId, SessionService sessions) =>
{
    var path = sessions.GetSessionFilePath(sessionId);
    if (path is null)
        return Results.NotFound(new { error = "Session not found" });

    return Results.File(path, "application/json", $"session-{sessionId}.json");
});

// ── SignalR hub ──────────────────────────────────────────────────
app.MapHub<TrackingHub>("/hubs/tracking");

app.Run();
