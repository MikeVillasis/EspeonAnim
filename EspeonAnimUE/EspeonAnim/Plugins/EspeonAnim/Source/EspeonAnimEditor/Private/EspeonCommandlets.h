#pragma once

#include "Commandlets/Commandlet.h"
#include "EspeonCommandlets.generated.h"

/** -run=EspeonDumpSkeleton -Skeleton=<asset path> -Out=<json path> [-Name=<profile name>] */
UCLASS()
class UEspeonDumpSkeletonCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonDumpSkeletonCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/** -run=EspeonImportMotion -Motion=<json> -Skeleton=<asset> -Dest=<package path> -Name=<asset> [-Fbx=<path>] */
UCLASS()
class UEspeonImportMotionCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonImportMotionCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/** -run=EspeonProcess -Input=<.espeon or video> [-Name=<AssetName>]: runs processor -> import -> FBX synchronously. Exit 0 = done. */
UCLASS()
class UEspeonProcessCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonProcessCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/** -run=EspeonServeTest [-Seconds=20] [-Port=N]: starts the Wi-Fi receiver and pumps it so it can be exercised with curl. */
UCLASS()
class UEspeonServeTestCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonServeTestCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/** -run=EspeonSelfTest: QR generation, pairing URL and token checks. Exit 0 = all passed. */
UCLASS()
class UEspeonSelfTestCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonSelfTestCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/**
 * -run=EspeonMakePreviewLevel -Anim=/Game/EspeonAnim/Animations/AS_x [-Map=/Game/EspeonAnim/Maps/L_EspeonPreview]
 * Rebuilds the preview level (floor, sun, sky, Manny looping the given AnimSequence, side-front camera) and saves it.
 */
UCLASS()
class UEspeonMakePreviewLevelCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonMakePreviewLevelCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/**
 * -run=EspeonRenderPreview -Anim=<asset> -Out=<folder> [-Width=960 -Height=540 -Fps=60 -Views=front,side]
 * Needs a real RHI: run WITHOUT -NullRHI and WITH -AllowCommandletRendering (and -RenderOffscreen if no display).
 * Renders PNG sequences via SceneCapture2D, then encodes mp4s / contact sheet with ffmpeg (must be on PATH).
 */
UCLASS()
class UEspeonRenderPreviewCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonRenderPreviewCommandlet();
	virtual int32 Main(const FString& Params) override;
};
