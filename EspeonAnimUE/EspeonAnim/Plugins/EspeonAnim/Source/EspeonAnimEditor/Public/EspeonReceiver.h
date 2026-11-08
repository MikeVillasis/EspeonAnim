#pragma once

#include "CoreMinimal.h"
#include "HttpRouteHandle.h"
#include "HttpResultCallback.h"
#include "HttpServerRequest.h"
#include "Containers/Ticker.h"

class IHttpRouter;

/**
 * Wi-Fi receiver (docs/formats.md section 5) on top of the engine's HTTPServer module.
 *
 * The module's listener binds "localhost" unless [HTTPServer.Listeners] says otherwise. Start() therefore writes a
 * per-port ListenerOverrides entry (BindAddress=any -> 0.0.0.0) into the in-memory engine ini before the listener is
 * created. Nothing is written to disk.
 */
class ESPEONANIMEDITOR_API FEspeonReceiver
{
public:
	static FEspeonReceiver& Get();

	bool Start(int32 Port, FString& OutError);
	void Stop();
	bool IsRunning() const { return bRunning; }
	int32 GetPort() const { return RunningPort; }

	/** 128-bit random token (32 hex chars) kept in the per-user editor settings (Saved/Config/.../EditorPerProjectUserSettings.ini). */
	static FString GetToken();
	static FString RegenerateToken();

	static FString GetPcName();
	/** Private IPv4 addresses of this PC, best candidate first. */
	static TArray<FString> GetLanAddresses();
	/** espeon://pair?v=1&h=<ip>&p=<port>&t=<token>&n=<pcname> */
	static FString BuildPairUrl(const FString& Host, int32 Port, const FString& Token);

	/** Largest request body accepted (docs: 1 GiB). */
	static constexpr int64 MaxBodyBytes = 1024ll * 1024 * 1024;

private:
	bool HandlePing(const FHttpServerRequest& Req, const FHttpResultCallback& Done);
	bool HandlePostPackage(const FHttpServerRequest& Req, const FHttpResultCallback& Done);
	bool HandleGetPackage(const FHttpServerRequest& Req, const FHttpResultCallback& Done);
	bool IsAuthorized(const FHttpServerRequest& Req) const;
	bool Pump(float DeltaTime);

	bool bRunning = false;
	int32 RunningPort = 0;
	TSharedPtr<IHttpRouter> Router;
	TArray<FHttpRouteHandle> Routes;
	FTSTicker::FDelegateHandle PumpHandle;
};
