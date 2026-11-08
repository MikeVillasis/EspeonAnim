#include "EspeonAnimSettings.h"
#include "Animation/Skeleton.h"
#include "Misc/Paths.h"

UEspeonAnimSettings::UEspeonAnimSettings()
{
	CategoryName = TEXT("Plugins");
	SectionName = TEXT("EspeonAnim");
	DestinationPath.Path = TEXT("/Game/EspeonAnim/Animations");
	TargetSkeleton = TSoftObjectPtr<USkeleton>(FSoftObjectPath(TEXT("/Game/Characters/Mannequins/Meshes/SK_Mannequin.SK_Mannequin")));
}

static FString RepoProcessorDir()
{
	// <repo>/EspeonAnimUE/EspeonAnim (project) -> <repo>/processor
	return FPaths::ConvertRelativePathToFull(FPaths::Combine(FPaths::ProjectDir(), TEXT("../../processor")));
}

FString UEspeonAnimSettings::GetResolvedPython() const
{
	if (!ProcessorPython.FilePath.IsEmpty())
	{
		return FPaths::ConvertRelativePathToFull(ProcessorPython.FilePath);
	}
	return FPaths::Combine(GetResolvedWorkingDir(), TEXT(".venv/Scripts/python.exe"));
}

FString UEspeonAnimSettings::GetResolvedWorkingDir() const
{
	if (!ProcessorWorkingDir.Path.IsEmpty())
	{
		return FPaths::ConvertRelativePathToFull(ProcessorWorkingDir.Path);
	}
	return RepoProcessorDir();
}

FString UEspeonAnimSettings::GetResolvedDestinationPath() const
{
	return DestinationPath.Path.IsEmpty() ? FString(TEXT("/Game/EspeonAnim/Animations")) : DestinationPath.Path;
}

FString UEspeonAnimSettings::GetResolvedFbxDir() const
{
	if (!FbxOutputDir.Path.IsEmpty()) return FPaths::ConvertRelativePathToFull(FbxOutputDir.Path);
	return FPaths::ConvertRelativePathToFull(FPaths::Combine(FPaths::ProjectSavedDir(), TEXT("EspeonAnim/Exports")));
}

FString UEspeonAnimSettings::GetResolvedJobsDir() const
{
	if (!JobsDir.Path.IsEmpty()) return FPaths::ConvertRelativePathToFull(JobsDir.Path);
	return FPaths::ConvertRelativePathToFull(FPaths::Combine(FPaths::ProjectSavedDir(), TEXT("EspeonAnim/Jobs")));
}
