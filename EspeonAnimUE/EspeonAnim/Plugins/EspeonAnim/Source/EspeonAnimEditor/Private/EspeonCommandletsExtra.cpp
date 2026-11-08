// Commandlets added with the job pipeline: EspeonProcess, EspeonServeTest, EspeonSelfTest, EspeonMakePreviewLevel.
#include "EspeonCommandlets.h"
#include "EspeonJobManager.h"
#include "EspeonQr.h"
#include "EspeonReceiver.h"

#include "Animation/AnimSequence.h"
#include "Animation/SkeletalMeshActor.h"
#include "Async/TaskGraphInterfaces.h"
#include "Camera/CameraActor.h"
#include "Camera/CameraComponent.h"
#include "Components/DirectionalLightComponent.h"
#include "Components/SkeletalMeshComponent.h"
#include "Components/SkyAtmosphereComponent.h"
#include "Components/SkyLightComponent.h"
#include "Components/StaticMeshComponent.h"
#include "Containers/Ticker.h"
#include "Editor.h"
#include "Engine/DirectionalLight.h"
#include "Engine/SkeletalMesh.h"
#include "Engine/SkyLight.h"
#include "Engine/StaticMesh.h"
#include "Engine/StaticMeshActor.h"
#include "Engine/Texture2D.h"
#include "FileHelpers.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformProcess.h"
#include "HAL/PlatformTime.h"
#include "Misc/PackageName.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonCmd2, Log, All);

// ------------------------------------------------------------------------------------------------ EspeonProcess

UEspeonProcessCommandlet::UEspeonProcessCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonProcessCommandlet::Main(const FString& Params)
{
	FString Input, Name;
	FParse::Value(*Params, TEXT("Input="), Input);
	FParse::Value(*Params, TEXT("Name="), Name);
	if (Input.IsEmpty())
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: -Input=<package or video> is required"));
		return 1;
	}
	FEspeonJobManager& Jobs = FEspeonJobManager::Get();
	FString Id, Err;
	if (!Jobs.CreateJobFromFile(Input, Id, Err))
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: %s"), *Err);
		return 1;
	}
	if (!Name.IsEmpty()) Jobs.SetNameOverride(Id, Name);
	const bool bOk = Jobs.RunSync(Id);
	FEspeonJobStatus S;
	Jobs.GetJob(Id, S);
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim: job %s state=%s progress=%.2f quality=%s message=%s"),
		*Id, EspeonJobStateToString(S.State), S.Progress, *S.Quality, *S.Message);
	if (bOk)
	{
		UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim: asset %s  fbx %s"), *S.AnimAssetPath, *S.FbxPath);
	}
	for (const FEspeonJobWarning& W : S.Warnings)
	{
		UE_LOG(LogEspeonCmd2, Warning, TEXT("EspeonAnim: warning %s: %s"), *W.Code, *W.Message);
	}
	return bOk ? 0 : 1;
}

// ------------------------------------------------------------------------------------------------ EspeonServeTest

UEspeonServeTestCommandlet::UEspeonServeTestCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonServeTestCommandlet::Main(const FString& Params)
{
	int32 Seconds = 20, Port = 47800;
	FParse::Value(*Params, TEXT("Seconds="), Seconds);
	FParse::Value(*Params, TEXT("Port="), Port);
	FString Err;
	if (!FEspeonReceiver::Get().Start(Port, Err))
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: %s"), *Err);
		return 1;
	}
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim: serve test on port %d for %d s. token=%s lan=%s"),
		Port, Seconds, *FEspeonReceiver::GetToken(), *FString::Join(FEspeonReceiver::GetLanAddresses(), TEXT(",")));
	const double End = FPlatformTime::Seconds() + Seconds;
	double Last = FPlatformTime::Seconds();
	while (FPlatformTime::Seconds() < End)
	{
		const double Now = FPlatformTime::Seconds();
		FTSTicker::GetCoreTicker().Tick(float(Now - Last));
		Last = Now;
		FTaskGraphInterface::Get().ProcessThreadUntilIdle(ENamedThreads::GameThread);
		FPlatformProcess::Sleep(0.002f);
	}
	FEspeonReceiver::Get().Stop();
	for (const FEspeonJobStatus& S : FEspeonJobManager::Get().GetJobs())
	{
		UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim: job %s state=%s message=%s"), *S.Id, EspeonJobStateToString(S.State), *S.Message);
	}
	return 0;
}

// ------------------------------------------------------------------------------------------------ EspeonSelfTest

UEspeonSelfTestCommandlet::UEspeonSelfTestCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonSelfTestCommandlet::Main(const FString&)
{
	int32 Failures = 0;
	auto Check = [&Failures](bool bCond, const TCHAR* What)
	{
		UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim selftest: %s - %s"), bCond ? TEXT("PASS") : TEXT("FAIL"), What);
		if (!bCond) ++Failures;
	};

	const FString Token = FEspeonReceiver::GetToken();
	Check(Token.Len() == 32 && Token == FEspeonReceiver::GetToken(), TEXT("token is 32 hex chars and stable"));
	const FString Token2 = FEspeonReceiver::RegenerateToken();
	Check(Token2.Len() == 32 && Token2 != Token && Token2 == FEspeonReceiver::GetToken(), TEXT("token regenerates and persists"));

	const FString Url = FEspeonReceiver::BuildPairUrl(TEXT("192.168.1.23"), 47800, Token2);
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim selftest: pair url %s"), *Url);
	Check(Url.StartsWith(TEXT("espeon://pair?v=1&h=192.168.1.23&p=47800&t=")) && Url.Contains(TEXT("&n=")), TEXT("pairing URL format"));

	int32 Size = 0;
	TArray<uint8> Px;
	FString E;
	const bool bOk = FEspeonQr::EncodePixels(Url, 6, 4, Size, Px, E);
	Check(bOk && Size > 0 && Px.Num() == Size * Size * 4, TEXT("QR pixels generated with consistent size"));
	if (bOk)
	{
		UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim selftest: QR image %dx%d"), Size, Size);
		auto Lum = [&](int32 X, int32 Y) { return Px[(Y * Size + X) * 4]; };
		Check(Lum(0, 0) == 255 && Lum(Size - 1, Size - 1) == 255, TEXT("quiet zone is white"));
		Check(Lum(4 * 6 + 3, 4 * 6 + 3) == 0, TEXT("top-left finder pattern corner is dark"));
		Check(Lum(Size - 4 * 6 - 3, 4 * 6 + 3) == 0 && Lum(4 * 6 + 3, Size - 4 * 6 - 3) == 0, TEXT("top-right and bottom-left finders are dark"));
		int32 Dark = 0;
		for (int32 i = 0; i < Size * Size; ++i) if (Px[i * 4] == 0) ++Dark;
		const float Ratio = float(Dark) / float(Size * Size);
		Check(Ratio > 0.2f && Ratio < 0.7f, TEXT("dark module ratio plausible"));
	}
	FString TexErr;
	UTexture2D* Tex = FEspeonQr::CreateTexture(Url, TexErr);
	Check(Tex && Tex->GetSizeX() == Size && Tex->GetSizeY() == Size, TEXT("transient UTexture2D created"));
	FString Overlong;
	for (int32 i = 0; i < 5000; ++i) Overlong += TEXT("x");
	FString OverErr;
	Check(!FEspeonQr::EncodePixels(Overlong, 4, 4, Size, Px, OverErr) && !OverErr.IsEmpty(), TEXT("over-long input fails cleanly (no crash)"));

	const TArray<FString> Ips = FEspeonReceiver::GetLanAddresses();
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim selftest: LAN addresses [%s] pc=%s"), *FString::Join(Ips, TEXT(", ")), *FEspeonReceiver::GetPcName());
	Check(Ips.Num() > 0, TEXT("at least one private IPv4 address found"));
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim selftest: %d failure(s)"), Failures);
	return Failures == 0 ? 0 : 1;
}

// ------------------------------------------------------------------------------------------------ EspeonMakePreviewLevel

UEspeonMakePreviewLevelCommandlet::UEspeonMakePreviewLevelCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonMakePreviewLevelCommandlet::Main(const FString& Params)
{
	FString AnimPath, MapPath = TEXT("/Game/EspeonAnim/Maps/L_EspeonPreview");
	FParse::Value(*Params, TEXT("Anim="), AnimPath);
	FParse::Value(*Params, TEXT("Map="), MapPath);
	if (AnimPath.IsEmpty())
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: -Anim=/Game/... (AnimSequence) is required"));
		return 1;
	}
	if (!AnimPath.Contains(TEXT("."))) AnimPath += TEXT(".") + FPackageName::GetShortName(AnimPath);
	UAnimSequence* Anim = LoadObject<UAnimSequence>(nullptr, *AnimPath);
	if (!Anim)
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: cannot load AnimSequence '%s'"), *AnimPath);
		return 1;
	}
	USkeletalMesh* Mesh = LoadObject<USkeletalMesh>(nullptr, TEXT("/Game/Characters/Mannequins/Meshes/SKM_Manny_Simple.SKM_Manny_Simple"));
	if (!Mesh)
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: SKM_Manny_Simple not found"));
		return 1;
	}

	GEditor->NewMap();
	UWorld* World = GEditor->GetEditorWorldContext().World();
	if (!World)
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: could not create a new map"));
		return 1;
	}
	FActorSpawnParameters P;
	P.SpawnCollisionHandlingOverride = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;

	// Floor: 30 m x 30 m slab whose top face is at Z = 0 (Manny's origin is at his feet).
	if (AStaticMeshActor* Floor = World->SpawnActor<AStaticMeshActor>(FVector(0, 0, -5), FRotator::ZeroRotator, P))
	{
		Floor->SetActorLabel(TEXT("Floor"));
		UStaticMeshComponent* C = Floor->GetStaticMeshComponent();
		C->SetMobility(EComponentMobility::Movable);
		C->SetStaticMesh(LoadObject<UStaticMesh>(nullptr, TEXT("/Engine/BasicShapes/Cube.Cube")));
		Floor->SetActorScale3D(FVector(30, 30, 0.1));
	}
	// Sun + atmosphere + sky light.
	if (ADirectionalLight* Sun = World->SpawnActor<ADirectionalLight>(FVector(0, 0, 500), FRotator(-45, 150, 0), P))
	{
		Sun->SetActorLabel(TEXT("Sun"));
		UDirectionalLightComponent* L = Cast<UDirectionalLightComponent>(Sun->GetLightComponent());
		L->SetMobility(EComponentMobility::Movable);
		L->SetAtmosphereSunLight(true);
	}
	if (ASkyAtmosphere* Atmo = World->SpawnActor<ASkyAtmosphere>(FVector::ZeroVector, FRotator::ZeroRotator, P))
	{
		Atmo->SetActorLabel(TEXT("SkyAtmosphere"));
	}
	if (ASkyLight* Sky = World->SpawnActor<ASkyLight>(FVector(0, 0, 300), FRotator::ZeroRotator, P))
	{
		Sky->SetActorLabel(TEXT("SkyLight"));
		USkyLightComponent* S = Sky->GetLightComponent();
		S->SetMobility(EComponentMobility::Movable);
		S->bRealTimeCapture = true;
		S->SourceType = SLS_CapturedScene;
	}
	// Character playing the animation on loop (animation mode "Use Animation Asset").
	if (ASkeletalMeshActor* Char = World->SpawnActor<ASkeletalMeshActor>(FVector::ZeroVector, FRotator::ZeroRotator, P))
	{
		Char->SetActorLabel(TEXT("Manny"));
		USkeletalMeshComponent* C = Char->GetSkeletalMeshComponent();
		C->SetMobility(EComponentMobility::Movable);
		C->SetSkeletalMesh(Mesh);
		C->SetAnimationMode(EAnimationMode::AnimationSingleNode);
		C->OverrideAnimationData(Anim, /*bIsLooping*/ true, /*bIsPlaying*/ true, 0.f, 1.f);
	}
	// Camera framing the character from the side-front (Manny faces +X).
	const FVector Target(0, 0, 95);
	const FVector CamLoc(300, 170, 130);
	if (ACameraActor* Cam = World->SpawnActor<ACameraActor>(CamLoc, (Target - CamLoc).Rotation(), P))
	{
		Cam->SetActorLabel(TEXT("PreviewCamera"));
		Cam->GetCameraComponent()->SetFieldOfView(60.f);
	}

	const FString Filename = FPackageName::LongPackageNameToFilename(MapPath, FPackageName::GetMapPackageExtension());
	IFileManager::Get().MakeDirectory(*FPaths::GetPath(Filename), true);
	if (!FEditorFileUtils::SaveLevel(World->PersistentLevel, Filename))
	{
		UE_LOG(LogEspeonCmd2, Error, TEXT("EspeonAnim: failed to save level '%s'"), *Filename);
		return 1;
	}
	UE_LOG(LogEspeonCmd2, Display, TEXT("EspeonAnim: preview level %s plays %s"), *MapPath, *Anim->GetPathName());
	return 0;
}
