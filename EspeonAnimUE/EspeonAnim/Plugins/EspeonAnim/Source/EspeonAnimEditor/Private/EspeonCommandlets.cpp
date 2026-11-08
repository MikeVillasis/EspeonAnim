#include "EspeonCommandlets.h"
#include "EspeonMotionImporter.h"
#include "Animation/Skeleton.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonCmd, Log, All);

namespace
{
	USkeleton* LoadSkeletonArg(const FString& Params)
	{
		FString Path;
		if (!FParse::Value(*Params, TEXT("Skeleton="), Path) || Path.IsEmpty())
		{
			UE_LOG(LogEspeonCmd, Error, TEXT("EspeonAnim: missing -Skeleton=<asset path>"));
			return nullptr;
		}
		USkeleton* Skel = LoadObject<USkeleton>(nullptr, *Path);
		if (!Skel)
		{
			UE_LOG(LogEspeonCmd, Error, TEXT("EspeonAnim: could not load skeleton '%s'"), *Path);
		}
		return Skel;
	}
}

UEspeonDumpSkeletonCommandlet::UEspeonDumpSkeletonCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonDumpSkeletonCommandlet::Main(const FString& Params)
{
	FString Out, Name = TEXT("ue5_manny");
	FParse::Value(*Params, TEXT("Out="), Out);
	FParse::Value(*Params, TEXT("Name="), Name);
	if (Out.IsEmpty())
	{
		UE_LOG(LogEspeonCmd, Error, TEXT("EspeonAnim: missing -Out=<json path>"));
		return 1;
	}
	USkeleton* Skel = LoadSkeletonArg(Params);
	if (!Skel) return 1;
	FString Err;
	if (!UEspeonMotionImporter::DumpSkeletonProfile(Skel, Out, Name, Err))
	{
		UE_LOG(LogEspeonCmd, Error, TEXT("%s"), *Err);
		return 1;
	}
	UE_LOG(LogEspeonCmd, Display, TEXT("EspeonAnim: skeleton profile written to %s"), *Out);
	return 0;
}

UEspeonImportMotionCommandlet::UEspeonImportMotionCommandlet()
{
	IsClient = false; IsEditor = true; IsServer = false; LogToConsole = true;
}

int32 UEspeonImportMotionCommandlet::Main(const FString& Params)
{
	FString Motion, Dest = TEXT("/Game/EspeonAnim/Animations"), Name, Fbx;
	FParse::Value(*Params, TEXT("Motion="), Motion);
	FParse::Value(*Params, TEXT("Dest="), Dest);
	FParse::Value(*Params, TEXT("Name="), Name);
	FParse::Value(*Params, TEXT("Fbx="), Fbx);
	if (Motion.IsEmpty() || Name.IsEmpty())
	{
		UE_LOG(LogEspeonCmd, Error, TEXT("EspeonAnim: -Motion=<json> and -Name=<AssetName> are required"));
		return 1;
	}
	USkeleton* Skel = LoadSkeletonArg(Params);
	if (!Skel) return 1;

	FEspeonImportResult R = UEspeonMotionImporter::ImportMotion(Motion, Skel, Dest, Name, Fbx);
	if (!R.bSuccess)
	{
		UE_LOG(LogEspeonCmd, Error, TEXT("%s"), *R.Error);
		return 1;
	}
	UE_LOG(LogEspeonCmd, Display, TEXT("EspeonAnim: imported %s"), *R.PackageName);
	return 0;
}
