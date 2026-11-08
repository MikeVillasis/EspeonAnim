#pragma once

#include "CoreMinimal.h"
#include "Engine/DeveloperSettings.h"
#include "Engine/EngineTypes.h"
#include "UObject/SoftObjectPtr.h"
#include "EspeonAnimSettings.generated.h"

class USkeleton;

/** Project Settings > Plugins > EspeonAnim. Empty path fields mean "use the automatic default" (see tooltips). */
UCLASS(config = EspeonAnim, defaultconfig, meta = (DisplayName = "EspeonAnim"))
class ESPEONANIMEDITOR_API UEspeonAnimSettings : public UDeveloperSettings
{
	GENERATED_BODY()
public:
	UEspeonAnimSettings();

	virtual FName GetCategoryName() const override { return TEXT("Plugins"); }

	static const UEspeonAnimSettings* Get() { return GetDefault<UEspeonAnimSettings>(); }

	/** Python interpreter of the processor venv. Empty = <Processor directory>/.venv/Scripts/python.exe. */
	UPROPERTY(config, EditAnywhere, Category = "Processor", meta = (FilePathFilter = "exe"))
	FFilePath ProcessorPython;

	/** Processor directory: contains the espeon_processor package, tools/setup_processor.ps1 and the .venv (the Install / Repair button installs into it). Empty = ../../processor relative to the project directory. */
	UPROPERTY(config, EditAnywhere, Category = "Processor")
	FDirectoryPath ProcessorWorkingDir;

	/** TCP port of the Wi-Fi receiver. */
	UPROPERTY(config, EditAnywhere, Category = "Receiver", meta = (ClampMin = "1024", ClampMax = "65535"))
	int32 ListenPort = 47800;

	/** Package path new AnimSequences are created in. */
	UPROPERTY(config, EditAnywhere, Category = "Import", meta = (ContentDir))
	FDirectoryPath DestinationPath;

	/** Skeleton the motion is imported onto. */
	UPROPERTY(config, EditAnywhere, Category = "Import")
	TSoftObjectPtr<USkeleton> TargetSkeleton;

	/** Also export an FBX next to each imported animation. */
	UPROPERTY(config, EditAnywhere, Category = "Import")
	bool bExportFbx = true;

	/** FBX output folder. Empty = <Project>/Saved/EspeonAnim/Exports. */
	UPROPERTY(config, EditAnywhere, Category = "Import")
	FDirectoryPath FbxOutputDir;

	/** Folder holding one sub-folder per job (input, processor output, log). Empty = <Project>/Saved/EspeonAnim/Jobs. */
	UPROPERTY(config, EditAnywhere, Category = "Import")
	FDirectoryPath JobsDir;

	// Resolved values (apply the defaults described above).
	FString GetResolvedPython() const;
	FString GetResolvedWorkingDir() const;
	FString GetResolvedDestinationPath() const;
	FString GetResolvedFbxDir() const;
	FString GetResolvedJobsDir() const;
};
