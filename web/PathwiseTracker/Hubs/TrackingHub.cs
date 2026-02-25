using Microsoft.AspNetCore.SignalR;
using PathwiseTracker.Services;

namespace PathwiseTracker.Hubs;

public sealed class TrackingHub : Hub
{
    private readonly SessionService _sessions;

    public TrackingHub(SessionService sessions)
    {
        _sessions = sessions;
    }

    /// <summary>
    /// Called by the browser to start a live tracking session.
    /// The client passes the webhook key it wants to listen to.
    /// </summary>
    public async Task StartSession(string webhookKey)
    {
        var session = _sessions.CreateSession(Context.ConnectionId, webhookKey);
        await Clients.Caller.SendAsync("SessionStarted", session.Id, webhookKey);
    }

    /// <summary>Browser requests a download of the current session data.</summary>
    public async Task RequestDownload()
    {
        var export = _sessions.GetActiveSessionExport(Context.ConnectionId);
        if (export is not null)
        {
            await Clients.Caller.SendAsync("DownloadReady", export);
        }
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        _sessions.EndSession(Context.ConnectionId);
        return base.OnDisconnectedAsync(exception);
    }
}
