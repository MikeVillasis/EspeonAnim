// EspeonRenderPreview: offline visual check of an imported AnimSequence (PNG sequences + ffmpeg mp4s + contact sheet).
#include "EspeonCommandlets.h"

#include "Animation/AnimSequence.h"
#include "Animation/SkeletalMeshActor.h"
#include "Components/DirectionalLightComponent.h"
#include "Components/SceneCaptureComponent2D.h"
#include "Components/SkeletalMeshComponent.h"
#include "Components/SkyLightComponent.h"
#include "Components/SkyAtmosphereComponent.h"
#include "AssetCompilingManager.h"
#include "Animation/AnimSingleNodeInstance.h"
#include "ContentStreaming.h"
#include "HAL/IConsoleManager.h"
#include "Misc/OutputDeviceNull.h"
#include "ShaderCompiler.h"
#include "Components/StaticMeshComponent.h"
#include "Engine/DirectionalLight.h"
#include "Engine/SceneCapture2D.h"
#include "Engine/SkeletalMesh.h"
#include "Engine/SkyLight.h"
#include "Engine/StaticMesh.h"
#include "Engine/StaticMeshActor.h"
#include "Engine/TextureRenderTarget2D.h"
#include "Editor.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformProcess.h"
#include "IImageWrapper.h"
#include "IImageWrapperModule.h"
#include "Materials/Material.h"
#include "Materials/MaterialInterface.h"
#include "Materials/MaterialExpressionAdd.h"
#include "Materials/MaterialExpressionComponentMask.h"
#include "Materials/MaterialExpressionFloor.h"
#include "Materials/MaterialExpressionFrac.h"
#include "Materials/MaterialExpressionMultiply.h"
#include "Materials/MaterialExpressionWorldPosition.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Modules/ModuleManager.h"
#include "RenderingThread.h"
#include "TextureResource.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonRender, Log, All);

namespace
{
	// 1 m world-space checker (two greys) so travel and foot sliding are visible against the floor.
	UMaterial* MakeCheckerMaterial()
	{
		UMaterial* Mat = NewObject<UMaterial>(GetTransientPackage(), NAME_None, RF_Transient);
		auto Make = [Mat](auto* Tag) { using T = std::remove_pointer_t<decltype(Tag)>; T* E = NewObject<T>(Mat); Mat->GetExpressionCollection().AddExpression(E); return E; };
		auto* WP = Make((UMaterialExpressionWorldPosition*)nullptr);
		auto* Scale = Make((UMaterialExpressionMultiply*)nullptr); Scale->A.Connect(0, WP); Scale->ConstB = 0.01f;
		auto* Fl = Make((UMaterialExpressionFloor*)nullptr); Fl->Input.Connect(0, Scale);
		auto* MR = Make((UMaterialExpressionComponentMask*)nullptr); MR->Input.Connect(0, Fl); MR->R = true; MR->G = false; MR->B = false; MR->A = false;
		auto* MG = Make((UMaterialExpressionComponentMask*)nullptr); MG->Input.Connect(0, Fl); MG->R = false; MG->G = true; MG->B = false; MG->A = false;
		auto* Sum = Make((UMaterialExpressionAdd*)nullptr); Sum->A.Connect(0, MR); Sum->B.Connect(0, MG);
		auto* Half = Make((UMaterialExpressionMultiply*)nullptr); Half->A.Connect(0, Sum); Half->ConstB = 0.5f;
		auto* Fr = Make((UMaterialExpressionFrac*)nullptr); Fr->Input.Connect(0, Half);          // 0 or 0.5
		auto* Contrast = Make((UMaterialExpressionMultiply*)nullptr); Contrast->A.Connect(0, Fr); Contrast->ConstB = 0.8f;
		auto* Base = Make((UMaterialExpressionAdd*)nullptr); Base->A.Connect(0, Contrast); Base->ConstB = 0.15f; // 0.15 / 0.55
		Mat->GetEditorOnlyData()->BaseColor.Connect(0, Base);
		Mat->PostEditChange();
		return Mat;
	}

	struct FView
	{
		FString Name;
		FVector Dir;   // horizontal direction from the character TOWARD the camera
	};

	bool RunFfmpeg(const FString& Args)
	{
		int32 Code = -1;
		FString Out, Err;
		FPlatformProcess::ExecProcess(TEXT("ffmpeg"), *Args, &Code, &Out, &Err);
		if (Code != 0)
		{
			UE_LOG(LogEspeonRender, Warning, TEXT("ffmpeg failed (%d): %s\n%s"), Code, *Args, *Err.Right(1500));
		}
		return Code == 0;
	}

	// Burned-in frame counter (index of the PNG in the sequence).
	const TCHAR* GDrawText = TEXT("drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='%{eif\\:n\\:d}':x=10:y=10:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.6");
}

UEspeonRenderPreviewCommandlet::UEspeonRenderPreviewCommandlet()
{
	// IsClient must be true: FRendererModule::AllocateScene only creates a render scene when GIsClient is set.
	IsClient = true; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonRenderPreviewCommandlet::Main(const FString& Params)
{
	const double T0 = FPlatformTime::Seconds();
	FString AnimPath, OutDir, ViewsStr = TEXT("front,side");
	int32 Width = 960, Height = 540, Fps = 60;
	FParse::Value(*Params, TEXT("Anim="), AnimPath);
	FParse::Value(*Params, TEXT("Out="), OutDir);
	FParse::Value(*Params, TEXT("Views="), ViewsStr);
	FParse::Value(*Params, TEXT("Width="), Width);
	FParse::Value(*Params, TEXT("Height="), Height);
	FParse::Value(*Params, TEXT("Fps="), Fps);
	if (AnimPath.IsEmpty() || OutDir.IsEmpty())
	{
		UE_LOG(LogEspeonRender, Error, TEXT("EspeonRenderPreview: -Anim=<asset path> and -Out=<folder> are required"));
		return 1;
	}
	if (GUsingNullRHI)
	{
		UE_LOG(LogEspeonRender, Error, TEXT("EspeonRenderPreview: null RHI in use. Do not pass -NullRHI; pass -AllowCommandletRendering."));
		return 1;
	}
	OutDir = FPaths::ConvertRelativePathToFull(OutDir);

	if (!AnimPath.Contains(TEXT("."))) AnimPath += TEXT(".") + FPackageName::GetShortName(AnimPath);
	UAnimSequence* Anim = LoadObject<UAnimSequence>(nullptr, *AnimPath);
	USkeletalMesh* Mesh = LoadObject<USkeletalMesh>(nullptr, TEXT("/Game/Characters/Mannequins/Meshes/SKM_Manny_Simple.SKM_Manny_Simple"));
	if (!Anim || !Mesh)
	{
		UE_LOG(LogEspeonRender, Error, TEXT("EspeonRenderPreview: cannot load anim '%s' or SKM_Manny_Simple"), *AnimPath);
		return 1;
	}

	// Anim space: the character faces +Y, so "front" camera sits at +Y looking toward -Y; "side" sits at +X.
	TArray<FView> Views;
	TArray<FString> ViewNames;
	ViewsStr.ParseIntoArray(ViewNames, TEXT(","), true);
	for (const FString& N : ViewNames)
	{
		const FString V = N.TrimStartAndEnd().ToLower();
		if (V == TEXT("front")) Views.Add({ V, FVector(0, 1, 0) });
		else if (V == TEXT("side")) Views.Add({ V, FVector(1, 0, 0) });
		else if (V == TEXT("back")) Views.Add({ V, FVector(0, -1, 0) });
		else if (V == TEXT("left")) Views.Add({ V, FVector(-1, 0, 0) });
		else UE_LOG(LogEspeonRender, Warning, TEXT("Unknown view '%s' ignored"), *V);
	}
	if (Views.IsEmpty()) { UE_LOG(LogEspeonRender, Error, TEXT("No valid views")); return 1; }

	// Virtual shadow maps never converge in a commandlet (no frame history) and show up as floor noise: use cascaded shadow maps.
	if (IConsoleVariable* CV = IConsoleManager::Get().FindConsoleVariable(TEXT("r.Shadow.Virtual.Enable"))) CV->Set(0);

	GEditor->NewMap();
	UWorld* World = GEditor->GetEditorWorldContext().World();
	if (!World) { UE_LOG(LogEspeonRender, Error, TEXT("No world")); return 1; }
	FActorSpawnParameters P;
	P.SpawnCollisionHandlingOverride = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;

	UMaterial* FloorMat = nullptr;
	// Huge floor, top face at Z=0, engine world-aligned grid material.
	if (AStaticMeshActor* Floor = World->SpawnActor<AStaticMeshActor>(FVector(0, 0, -50), FRotator::ZeroRotator, P))
	{
		UStaticMeshComponent* C = Floor->GetStaticMeshComponent();
		C->SetMobility(EComponentMobility::Movable);
		C->SetStaticMesh(LoadObject<UStaticMesh>(nullptr, TEXT("/Engine/BasicShapes/Cube.Cube")));
		FloorMat = MakeCheckerMaterial();
		C->SetMaterial(0, FloorMat);
		Floor->SetActorScale3D(FVector(400, 400, 1)); // 400 m
	}
	if (ADirectionalLight* Sun = World->SpawnActor<ADirectionalLight>(FVector(0, 0, 500), FRotator(-50, 135, 0), P))
	{
		UDirectionalLightComponent* L = Cast<UDirectionalLightComponent>(Sun->GetLightComponent());
		L->SetMobility(EComponentMobility::Movable);
		L->SetIntensity(8.f);
		L->SetAtmosphereSunLight(true);
	}
	World->SpawnActor<ASkyAtmosphere>(FVector::ZeroVector, FRotator::ZeroRotator, P);
	if (ASkyLight* Sky = World->SpawnActor<ASkyLight>(FVector(0, 0, 300), FRotator::ZeroRotator, P))
	{
		USkyLightComponent* S = Sky->GetLightComponent();
		S->SetMobility(EComponentMobility::Movable);
		S->SetIntensity(1.f);
		S->SetLowerHemisphereColor(FLinearColor(0.2f, 0.2f, 0.2f));
		S->SourceType = SLS_CapturedScene;
		S->RecaptureSky();
	}

	ASkeletalMeshActor* Char = World->SpawnActor<ASkeletalMeshActor>(FVector::ZeroVector, FRotator::ZeroRotator, P);
	USkeletalMeshComponent* SK = Char->GetSkeletalMeshComponent();
	SK->SetMobility(EComponentMobility::Movable);
	SK->SetSkeletalMesh(Mesh);
	SK->SetAnimationMode(EAnimationMode::AnimationSingleNode);
	SK->SetUpdateAnimationInEditor(true); // editor-type world: anim ticking is gated by this flag
	SK->VisibilityBasedAnimTickOption = EVisibilityBasedAnimTickOption::AlwaysTickPoseAndRefreshBones;
	SK->bEnableUpdateRateOptimizations = false;
	SK->InitAnim(true);
	SK->PlayAnimation(Anim, /*bLooping*/ false);
	if (UAnimSingleNodeInstance* SN = SK->GetSingleNodeInstance()) { SN->SetPlaying(true); SN->SetPlayRate(0.f); }

	const int32 NumFrames = Anim->GetNumberOfSampledKeys();
	const double SrcFps = Anim->GetSamplingFrameRate().AsDecimal();
	UE_LOG(LogEspeonRender, Display, TEXT("EspeonRenderPreview: %s frames=%d srcFps=%.2f len=%.3fs rootMotion=%d rootLock=%d views=%d %dx%d"),
		*Anim->GetName(), NumFrames, SrcFps, Anim->GetPlayLength(), Anim->bEnableRootMotion, (int32)Anim->RootMotionRootLock, Views.Num(), Width, Height);

	TArray<UTextureRenderTarget2D*> RTs;
	TArray<USceneCaptureComponent2D*> Caps;
	for (const FView& V : Views)
	{
		UTextureRenderTarget2D* RT = NewObject<UTextureRenderTarget2D>(GetTransientPackage());
		RT->RenderTargetFormat = RTF_RGBA8;
		RT->bAutoGenerateMips = false;
		RT->ClearColor = FLinearColor::Black;
		RT->InitAutoFormat(Width, Height);
		RT->UpdateResourceImmediate(true);
		RTs.Add(RT);
		ASceneCapture2D* CapActor = World->SpawnActor<ASceneCapture2D>(FVector::ZeroVector, FRotator::ZeroRotator, P);
		USceneCaptureComponent2D* Cap = CapActor->GetCaptureComponent2D();
		Cap->TextureTarget = RT;
		Cap->CaptureSource = SCS_FinalColorLDR;
		Cap->bCaptureEveryFrame = false;
		Cap->bCaptureOnMovement = false;
		Cap->bAlwaysPersistRenderingState = true;
		Cap->ProjectionType = ECameraProjectionMode::Perspective;
		Cap->FOVAngle = 60.f; // horizontal; at 540 cm distance on 16:9 this frames ~3.5 m vertically
		Cap->PostProcessBlendWeight = 1.f;
		Cap->PostProcessSettings.bOverride_AutoExposureMethod = true;
		Cap->PostProcessSettings.AutoExposureMethod = AEM_Manual;
		// Lumen is slow and flickers in a transient world; plain skylight ambient + sun is enough for a pose check.
		Cap->PostProcessSettings.bOverride_DynamicGlobalIlluminationMethod = true;
		Cap->PostProcessSettings.DynamicGlobalIlluminationMethod = EDynamicGlobalIlluminationMethod::None;
		Cap->PostProcessSettings.bOverride_ReflectionMethod = true;
		Cap->PostProcessSettings.ReflectionMethod = EReflectionMethod::None;
		Cap->PostProcessSettings.bOverride_AutoExposureBias = true;
		Cap->PostProcessSettings.AutoExposureBias = 11.2f;
		Caps.Add(Cap);
		IFileManager::Get().MakeDirectory(*(OutDir / V.Name), true);
		TArray<FString> Old;
		IFileManager::Get().FindFiles(Old, *(OutDir / V.Name / TEXT("frame_*.png")), true, false);
		for (const FString& F : Old) IFileManager::Get().Delete(*(OutDir / V.Name / F));
	}

	// Materials/meshes/shaders compile asynchronously; without this the first frames render black/default.
	if (FloorMat) FloorMat->ForceRecompileForRendering();
	FAssetCompilingManager::Get().FinishAllCompilation();
	if (GShaderCompilingManager) GShaderCompilingManager->FinishAllCompilation();
	if (IConsoleVariable* CV = IConsoleManager::Get().FindConsoleVariable(TEXT("r.Streaming.FullyLoadUsedTextures"))) CV->Set(1);
	IStreamingManager::Get().StreamAllResources(15.f); // grid texture mips
	if (FloorMat)
	{
		FMaterialResource* MR = FloorMat->GetMaterialResource(GMaxRHIShaderPlatform);
		UE_LOG(LogEspeonRender, Display, TEXT("Floor material: resource=%d compiled=%d errors=%d"), MR != nullptr, MR && MR->IsGameThreadShaderMapComplete(), MR ? MR->GetCompileErrors().Num() : -1);
		if (MR) for (const FString& E : MR->GetCompileErrors()) UE_LOG(LogEspeonRender, Warning, TEXT("  %s"), *E);
	}

	IImageWrapperModule& ImgMod = FModuleManager::LoadModuleChecked<IImageWrapperModule>(TEXT("ImageWrapper"));

	// Root motion: the pose is sampled directly (single-node SetPosition + TickAnimation + RefreshBoneTransforms). The sequence has
	// bEnableRootMotion, so the evaluated pose has the root bone locked at the component origin (verified: pelvis XY stays 0).
	// The accumulated motion for [0, Time] comes from ExtractRootMotionFromRange and is applied to the actor transform each frame,
	// which is what the character movement component does at runtime. Cameras follow the actor horizontally at a fixed height.
	int32 Written = 0;
	FString Csv = TEXT("frame,time,actorX,actorY,actorZ,pelvisZ,footL_X,footL_Y,footL_Z,footR_X,footR_Y,footR_Z\n");
	for (int32 F = -3; F < NumFrames; ++F) // F<0: warm-up captures (not written) so sky/shadows/exposure settle
	{
		++GFrameCounter; // no engine tick runs in a commandlet; skinning/render-state updates are keyed on the frame counter
		const bool bWarmup = F < 0;
		if (F == 0) { IStreamingManager::Get().StreamAllResources(15.f); FlushRenderingCommands(); }
		const float Time = FMath::Min(float(FMath::Max(F, 0) / SrcFps), Anim->GetPlayLength());
		if (UAnimSingleNodeInstance* SN = SK->GetSingleNodeInstance()) { SN->SetPosition(Time, false); }
		SK->TickAnimation(1.f / 60.f, false);
		SK->RefreshBoneTransforms();
		SK->FinalizeBoneTransform();
		// Apply the sequence's accumulated root motion (frame 0 -> Time) to the actor so the character moves in world space.
		const FTransform RM = Anim->ExtractRootMotionFromRange(0.0, Time, FAnimExtractContext(0.0, true));
		Char->SetActorTransform(RM, false, nullptr, ETeleportType::TeleportPhysics);
		SK->UpdateComponentToWorld();
		SK->MarkRenderTransformDirty();
		SK->MarkRenderDynamicDataDirty();
		World->SendAllEndOfFrameUpdates();

		const FVector Root = Char->GetActorLocation();
		if (!bWarmup && (F == 0 || F == NumFrames / 2 || F == NumFrames - 1 || F % 30 == 0))
		{
			UE_LOG(LogEspeonRender, Display, TEXT("  frame %d t=%.3f rootBone=%s pelvis=%s extractedRM=%s yaw=%.1f"), F, Time, *Root.ToString(),
				*SK->GetBoneTransform(1).GetLocation().ToString(), *RM.GetTranslation().ToString(), RM.Rotator().Yaw);
		}
		if (!bWarmup)
		{
			const FVector FL = SK->GetBoneLocation(TEXT("foot_l"), EBoneSpaces::WorldSpace), FR = SK->GetBoneLocation(TEXT("foot_r"), EBoneSpaces::WorldSpace);
			Csv += FString::Printf(TEXT("%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n"), F, Time, Root.X, Root.Y, Root.Z,
				SK->GetBoneLocation(TEXT("pelvis"), EBoneSpaces::WorldSpace).Z, FL.X, FL.Y, FL.Z, FR.X, FR.Y, FR.Z);
		}
		const FVector Focus(Root.X, Root.Y, 140.f);
		for (int32 i = 0; i < Views.Num(); ++i)
		{
			Caps[i]->SetWorldLocationAndRotation(Focus + Views[i].Dir * 540.0, (-Views[i].Dir).Rotation());
			Caps[i]->UpdateComponentToWorld();
			World->SendAllEndOfFrameUpdates();
			Caps[i]->CaptureScene();
			FlushRenderingCommands();

			TArray<FColor> Pixels;
			FTextureRenderTargetResource* Res = RTs[i]->GameThread_GetRenderTargetResource();
			if (bWarmup) continue;
			if (!Res->ReadPixels(Pixels) || Pixels.Num() != Width * Height)
			{
				UE_LOG(LogEspeonRender, Error, TEXT("ReadPixels failed on frame %d view %s"), F, *Views[i].Name);
				return 1;
			}
			for (FColor& C : Pixels) C.A = 255;
			TSharedPtr<IImageWrapper> W = ImgMod.CreateImageWrapper(EImageFormat::PNG);
			W->SetRaw(Pixels.GetData(), Pixels.Num() * sizeof(FColor), Width, Height, ERGBFormat::BGRA, 8);
			const TArray64<uint8>& Png = W->GetCompressed();
			FFileHelper::SaveArrayToFile(Png, *(OutDir / Views[i].Name / FString::Printf(TEXT("frame_%04d.png"), F)));
			++Written;
		}
	}
	FFileHelper::SaveStringToFile(Csv, *(OutDir / TEXT("motion.csv")));
	UE_LOG(LogEspeonRender, Display, TEXT("EspeonRenderPreview: wrote %d PNGs in %.1fs"), Written, FPlatformTime::Seconds() - T0);

	// ---- ffmpeg encodes
	const FString FpsS = FString::FromInt(Fps);
	TArray<FString> Mp4s;
	for (const FView& V : Views)
	{
		const FString Mp4 = OutDir / (V.Name + TEXT(".mp4"));
		const FString Base = FString::Printf(TEXT("-y -hide_banner -loglevel error -framerate %s -i \"%s\" "), *FpsS, *(OutDir / V.Name / TEXT("frame_%04d.png")));
		const FString Tail = FString::Printf(TEXT("-c:v libx264 -pix_fmt yuv420p -crf 18 \"%s\""), *Mp4);
		if (!RunFfmpeg(Base + FString::Printf(TEXT("-vf \"%s\" "), GDrawText) + Tail))
		{
			RunFfmpeg(Base + Tail);
		}
		Mp4s.Add(Mp4);
	}
	const FString PreviewMp4 = OutDir / TEXT("preview.mp4");
	if (Views.Num() == 1)
	{
		IFileManager::Get().Copy(*PreviewMp4, *Mp4s[0]);
	}
	else
	{
		FString In;
		for (const FString& M : Mp4s) In += FString::Printf(TEXT("-i \"%s\" "), *M);
		RunFfmpeg(FString::Printf(TEXT("-y -hide_banner -loglevel error %s-filter_complex \"hstack=inputs=%d\" -c:v libx264 -pix_fmt yuv420p -crf 18 \"%s\""),
			*In, Views.Num(), *PreviewMp4));
	}

	// Contact sheet: 8 evenly spaced frames per view (4x2 tiles), views stacked vertically.
	FString Select;
	const int32 Count = FMath::Min(8, NumFrames);
	for (int32 k = 0; k < Count; ++k)
	{
		const int32 Fr = Count > 1 ? FMath::RoundToInt(float(k) * (NumFrames - 1) / (Count - 1)) : 0;
		Select += FString::Printf(TEXT("%seq(n\\,%d)"), k ? TEXT("+") : TEXT(""), Fr);
	}
	TArray<FString> Sheets;
	for (const FView& V : Views)
	{
		const FString Sheet = OutDir / (V.Name + TEXT("_sheet.png"));
		const FString Base = FString::Printf(TEXT("-y -hide_banner -loglevel error -framerate %s -i \"%s\" "), *FpsS, *(OutDir / V.Name / TEXT("frame_%04d.png")));
		const FString Tail = FString::Printf(TEXT("-fps_mode vfr -frames:v 1 \"%s\""), *Sheet);
		const FString Post = TEXT("select='") + Select + TEXT("',scale=480:-1,tile=4x2");
		if (!RunFfmpeg(Base + FString::Printf(TEXT("-vf \"%s,%s\" "), GDrawText, *Post) + Tail))
		{
			RunFfmpeg(Base + FString::Printf(TEXT("-vf \"%s\" "), *Post) + Tail);
		}
		Sheets.Add(Sheet);
	}
	const FString SheetOut = OutDir / TEXT("contact_sheet.png");
	if (Sheets.Num() == 1)
	{
		IFileManager::Get().Copy(*SheetOut, *Sheets[0]);
	}
	else
	{
		FString In;
		for (const FString& S : Sheets) In += FString::Printf(TEXT("-i \"%s\" "), *S);
		RunFfmpeg(FString::Printf(TEXT("-y -hide_banner -loglevel error %s-filter_complex \"vstack=inputs=%d\" -frames:v 1 \"%s\""), *In, Sheets.Num(), *SheetOut));
	}

	const bool bOk = IFileManager::Get().FileExists(*SheetOut) && IFileManager::Get().FileExists(*PreviewMp4);
	UE_LOG(LogEspeonRender, Display, TEXT("EspeonRenderPreview: %s, total %.1fs -> %s"), bOk ? TEXT("DONE") : TEXT("FAILED encode"), FPlatformTime::Seconds() - T0, *OutDir);
	return bOk ? 0 : 1;
}
