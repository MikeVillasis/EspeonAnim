#include "EspeonReceiver.h"
#include "EspeonJobManager.h"

#include "Dom/JsonObject.h"
#include "HAL/PlatformProcess.h"
#include "HAL/PlatformTime.h"
#include "HttpPath.h"
#include "HttpServerModule.h"
#include "HttpServerResponse.h"
#include "IHttpRouter.h"
#include "Interfaces/IPv4/IPv4Address.h"
#include "Misc/ConfigCacheIni.h"
#include "Misc/CoreDelegates.h"
#include "Misc/FileHelper.h"
#include "Misc/Guid.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "SocketSubsystem.h"
#include "Sockets.h"
#include "GenericPlatform/GenericPlatformHttp.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonNet, Log, All);

namespace
{
	const TCHAR* GTokenSection = TEXT("EspeonAnim");
	const TCHAR* GTokenKey = TEXT("PairingToken");
	const TCHAR* GAppVersion = TEXT("0.1.0");

	TUniquePtr<FHttpServerResponse> JsonResponse(EHttpServerResponseCodes Code, const TSharedRef<FJsonObject>& Obj)
	{
		FString Text;
		TSharedRef<TJsonWriter<TCHAR, TCondensedJsonPrintPolicy<TCHAR>>> W = TJsonWriterFactory<TCHAR, TCondensedJsonPrintPolicy<TCHAR>>::Create(&Text);
		FJsonSerializer::Serialize(Obj, W);
		TUniquePtr<FHttpServerResponse> Resp = FHttpServerResponse::Create(Text, TEXT("application/json"));
		Resp->Code = Code;
		return Resp;
	}

	TUniquePtr<FHttpServerResponse> ErrorResponse(EHttpServerResponseCodes Code, const FString& Message)
	{
		TSharedRef<FJsonObject> O = MakeShared<FJsonObject>();
		O->SetStringField(TEXT("error"), Message);
		return JsonResponse(Code, O);
	}

	/** Constant-time string compare so the token cannot be probed byte by byte. */
	bool SecureEquals(const FString& A, const FString& B)
	{
		if (A.Len() != B.Len()) return false;
		uint32 Diff = 0;
		for (int32 i = 0; i < A.Len(); ++i) Diff |= uint32(A[i]) ^ uint32(B[i]);
		return Diff == 0;
	}

	void NotifyIniChanged(const TCHAR* Section)
	{
		TSet<FString> Sections;
		Sections.Add(Section);
		FCoreDelegates::TSOnConfigSectionsChanged().Broadcast(GEngineIni, Sections);
	}

	/** Makes the HTTPServer listener for Port bind 0.0.0.0 (default is localhost) and poll sockets without waiting. */
	void ConfigureHttpServer(int32 Port)
	{
		const TCHAR* Listeners = TEXT("HTTPServer.Listeners");
		TArray<FString> Overrides;
		GConfig->GetArray(Listeners, TEXT("ListenerOverrides"), Overrides, GEngineIni);
		const FString Prefix = FString::Printf(TEXT("(Port=%d,"), Port);
		Overrides.RemoveAll([&](const FString& S) { return S.TrimStart().StartsWith(Prefix); });
		Overrides.Add(FString::Printf(TEXT("(Port=%d,BindAddress=any)"), Port));
		GConfig->SetArray(Listeners, TEXT("ListenerOverrides"), Overrides, GEngineIni);
		NotifyIniChanged(Listeners);

		// We pump the server many times per frame (see Pump); a 1 ms wait per idle connection per pump would waste time.
		GConfig->SetFloat(TEXT("HTTPServer.Connections"), TEXT("BeginReadWaitTimeMS"), 0.f, GEngineIni);
		NotifyIniChanged(TEXT("HTTPServer.Connections"));
	}

	bool IsPortFree(int32 Port)
	{
		ISocketSubsystem* SS = ISocketSubsystem::Get(PLATFORM_SOCKETSUBSYSTEM);
		if (!SS) return true;
		FSocket* S = SS->CreateSocket(NAME_Stream, TEXT("EspeonPortProbe"), false);
		if (!S) return true;
		TSharedRef<FInternetAddr> Addr = SS->CreateInternetAddr();
		Addr->SetAnyAddress();
		Addr->SetPort(Port);
		const bool bOk = S->Bind(*Addr);
		S->Close();
		SS->DestroySocket(S);
		return bOk;
	}
}

FEspeonReceiver& FEspeonReceiver::Get()
{
	static FEspeonReceiver Instance;
	return Instance;
}

// ---------------------------------------------------------------- token / network helpers

FString FEspeonReceiver::GetToken()
{
	FString Token;
	GConfig->GetString(GTokenSection, GTokenKey, Token, GEditorPerProjectIni);
	if (Token.Len() != 32) Token = RegenerateToken();
	return Token;
}

FString FEspeonReceiver::RegenerateToken()
{
	const FString Token = FGuid::NewGuid().ToString(EGuidFormats::Digits).ToLower(); // 128 bits, 32 hex chars
	GConfig->SetString(GTokenSection, GTokenKey, *Token, GEditorPerProjectIni);
	GConfig->Flush(false, GEditorPerProjectIni);
	return Token;
}

FString FEspeonReceiver::GetPcName()
{
	return FPlatformProcess::ComputerName();
}

TArray<FString> FEspeonReceiver::GetLanAddresses()
{
	struct FCand { FString Ip; int32 Rank; };
	TArray<FCand> Cands;
	ISocketSubsystem* SS = ISocketSubsystem::Get(PLATFORM_SOCKETSUBSYSTEM);
	if (SS)
	{
		TArray<TSharedPtr<FInternetAddr>> Addrs;
		SS->GetLocalAdapterAddresses(Addrs);
		for (const TSharedPtr<FInternetAddr>& A : Addrs)
		{
			if (!A.IsValid()) continue;
			uint32 Ip = 0;
			A->GetIp(Ip);
			const uint32 B0 = (Ip >> 24) & 0xFF, B1 = (Ip >> 16) & 0xFF;
			const FString Text = FString::Printf(TEXT("%u.%u.%u.%u"), B0, B1, (Ip >> 8) & 0xFF, Ip & 0xFF);
			if (A->GetProtocolType() != FNetworkProtocolTypes::IPv4 || Ip == 0 || B0 == 127 || (B0 == 169 && B1 == 254)) continue;
			int32 Rank = 9; // public / other
			if (B0 == 192 && B1 == 168) Rank = 0;
			else if (B0 == 10) Rank = 1;
			else if (B0 == 172 && B1 >= 16 && B1 <= 31) Rank = 2; // often WSL / Hyper-V virtual switches too
			if (!Cands.ContainsByPredicate([&](const FCand& C) { return C.Ip == Text; })) Cands.Add({ Text, Rank });
		}
	}
	Cands.StableSort([](const FCand& A, const FCand& B) { return A.Rank < B.Rank; });
	TArray<FString> Out;
	for (const FCand& C : Cands) Out.Add(C.Ip);
	return Out;
}

FString FEspeonReceiver::BuildPairUrl(const FString& Host, int32 Port, const FString& Token)
{
	return FString::Printf(TEXT("espeon://pair?v=1&h=%s&p=%d&t=%s&n=%s"),
		*Host, Port, *Token, *FGenericPlatformHttp::UrlEncode(GetPcName()));
}

// ---------------------------------------------------------------- start / stop

bool FEspeonReceiver::Start(int32 Port, FString& OutError)
{
	if (bRunning)
	{
		if (RunningPort == Port) return true;
		Stop();
	}
	if (!IsPortFree(Port))
	{
		OutError = FString::Printf(TEXT("Port %d is already in use. Change the port in Project Settings > Plugins > EspeonAnim."), Port);
		return false;
	}
	ConfigureHttpServer(Port);

	FHttpServerModule& Module = FHttpServerModule::Get();
	Router = Module.GetHttpRouter(Port, false);
	if (!Router.IsValid())
	{
		OutError = TEXT("Could not create an HTTP router");
		return false;
	}
	Routes.Reset();
	Routes.Add(Router->BindRoute(FHttpPath(TEXT("/api/v1/ping")), EHttpServerRequestVerbs::VERB_GET,
		FHttpRequestHandler::CreateRaw(this, &FEspeonReceiver::HandlePing)));
	Routes.Add(Router->BindRoute(FHttpPath(TEXT("/api/v1/packages")), EHttpServerRequestVerbs::VERB_POST,
		FHttpRequestHandler::CreateRaw(this, &FEspeonReceiver::HandlePostPackage)));
	Routes.Add(Router->BindRoute(FHttpPath(TEXT("/api/v1/packages/:id")), EHttpServerRequestVerbs::VERB_GET,
		FHttpRequestHandler::CreateRaw(this, &FEspeonReceiver::HandleGetPackage)));
	Module.StartAllListeners();

	// The module ticks the server once per engine frame and reads at most 64 KiB per connection per tick, which would
	// cap uploads at a few MB/s. Pump it repeatedly (time-boxed) each frame instead.
	PumpHandle = FTSTicker::GetCoreTicker().AddTicker(TEXT("EspeonHttpPump"), 0.f,
		[this](float Dt) { return Pump(Dt); });

	bRunning = true;
	RunningPort = Port;
	UE_LOG(LogEspeonNet, Display, TEXT("EspeonAnim receiver listening on 0.0.0.0:%d"), Port);
	return true;
}

void FEspeonReceiver::Stop()
{
	if (!bRunning) return;
	FTSTicker::GetCoreTicker().RemoveTicker(PumpHandle);
	PumpHandle.Reset();
	if (Router.IsValid())
	{
		for (const FHttpRouteHandle& H : Routes) Router->UnbindRoute(H);
	}
	Routes.Reset();
	Router.Reset();
	if (FHttpServerModule::IsAvailable()) FHttpServerModule::Get().StopAllListeners();
	bRunning = false;
	UE_LOG(LogEspeonNet, Display, TEXT("EspeonAnim receiver stopped"));
}

bool FEspeonReceiver::Pump(float)
{
	if (!bRunning || !FHttpServerModule::IsAvailable()) return true;
	const double End = FPlatformTime::Seconds() + 0.008;
	for (int32 i = 0; i < 256 && FPlatformTime::Seconds() < End; ++i)
	{
		FHttpServerModule::Get().Tick(0.f);
	}
	return true;
}

// ---------------------------------------------------------------- handlers

bool FEspeonReceiver::IsAuthorized(const FHttpServerRequest& Req) const
{
	for (const auto& H : Req.Headers)
	{
		if (!H.Key.Equals(TEXT("Authorization"), ESearchCase::IgnoreCase) || H.Value.Num() == 0) continue;
		const FString V = H.Value[0].TrimStartAndEnd();
		if (!V.StartsWith(TEXT("Bearer "), ESearchCase::IgnoreCase)) return false;
		return SecureEquals(V.Mid(7).TrimStartAndEnd(), GetToken());
	}
	return false;
}

bool FEspeonReceiver::HandlePing(const FHttpServerRequest&, const FHttpResultCallback& Done)
{
	TSharedRef<FJsonObject> O = MakeShared<FJsonObject>();
	O->SetStringField(TEXT("name"), GetPcName());
	O->SetStringField(TEXT("app"), TEXT("EspeonAnim"));
	O->SetStringField(TEXT("version"), GAppVersion);
	O->SetNumberField(TEXT("api"), 1);
	Done(JsonResponse(EHttpServerResponseCodes::Ok, O));
	return true;
}

bool FEspeonReceiver::HandlePostPackage(const FHttpServerRequest& Req, const FHttpResultCallback& Done)
{
	if (!IsAuthorized(Req))
	{
		Done(ErrorResponse(EHttpServerResponseCodes::Denied, TEXT("Missing or wrong pairing token")));
		return true;
	}
	if (Req.Body.Num() == 0)
	{
		Done(ErrorResponse(EHttpServerResponseCodes::BadRequest, TEXT("Empty body")));
		return true;
	}
	if (int64(Req.Body.Num()) > MaxBodyBytes)
	{
		Done(ErrorResponse(EHttpServerResponseCodes::RequestTooLarge, TEXT("Package larger than 1 GiB")));
		return true;
	}
	FString Id, InputPath, Error;
	FEspeonJobManager& Jobs = FEspeonJobManager::Get();
	if (!Jobs.CreateJob(TEXT("espeon"), TEXT("package"), Id, InputPath, Error)
		|| !FFileHelper::SaveArrayToFile(Req.Body, *InputPath))
	{
		Done(ErrorResponse(EHttpServerResponseCodes::ServerError, Error.IsEmpty() ? FString(TEXT("Could not store the package")) : Error));
		return true;
	}
	UE_LOG(LogEspeonNet, Display, TEXT("EspeonAnim: received package %s (%d bytes)"), *Id, Req.Body.Num());
	Jobs.RunAsync(Id);

	TSharedRef<FJsonObject> O = MakeShared<FJsonObject>();
	O->SetStringField(TEXT("id"), Id);
	Done(JsonResponse(EHttpServerResponseCodes::Created, O));
	return true;
}

bool FEspeonReceiver::HandleGetPackage(const FHttpServerRequest& Req, const FHttpResultCallback& Done)
{
	if (!IsAuthorized(Req))
	{
		Done(ErrorResponse(EHttpServerResponseCodes::Denied, TEXT("Missing or wrong pairing token")));
		return true;
	}
	const FString* Id = Req.PathParams.Find(TEXT("id"));
	FEspeonJobStatus S;
	if (!Id || !FEspeonJobManager::Get().GetJob(*Id, S))
	{
		Done(ErrorResponse(EHttpServerResponseCodes::NotFound, TEXT("Unknown package id")));
		return true;
	}
	TSharedRef<FJsonObject> O = MakeShared<FJsonObject>();
	O->SetStringField(TEXT("id"), S.Id);
	O->SetStringField(TEXT("state"), EspeonJobStateToString(S.State));
	O->SetNumberField(TEXT("progress"), S.Progress);
	O->SetStringField(TEXT("message"), S.Message);
	O->SetStringField(TEXT("quality"), S.Quality);
	TArray<TSharedPtr<FJsonValue>> Warnings;
	for (const FEspeonJobWarning& W : S.Warnings)
	{
		TSharedRef<FJsonObject> WO = MakeShared<FJsonObject>();
		WO->SetStringField(TEXT("code"), W.Code);
		WO->SetStringField(TEXT("message"), W.Message);
		WO->SetStringField(TEXT("suggestion"), W.Suggestion);
		Warnings.Add(MakeShared<FJsonValueObject>(WO));
	}
	O->SetArrayField(TEXT("warnings"), Warnings);
	Done(JsonResponse(EHttpServerResponseCodes::Ok, O));
	return true;
}
