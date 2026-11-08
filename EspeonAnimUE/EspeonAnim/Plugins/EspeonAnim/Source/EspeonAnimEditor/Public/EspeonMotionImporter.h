#pragma once

#include "CoreMinimal.h"
#include "UObject/Object.h"
#include "EspeonMotionImporter.generated.h"

class USkeleton;
class UAnimSequence;

/** Result of an import; Error is set (and prefixed "EspeonAnim:") on failure. */
struct FEspeonImportResult
{
	bool bSuccess = false;
	FString Error;
	FString PackageName;
	FString FbxPath;
};

/**
 * Static helpers used by the commandlets and the editor menu.
 * motion.json is described in docs/formats.md section 2.
 */
UCLASS()
class ESPEONANIMEDITOR_API UEspeonMotionImporter : public UObject
{
	GENERATED_BODY()
public:
	/** Writes the skeleton profile JSON (names, parents, ref local + component transforms). */
	static bool DumpSkeletonProfile(USkeleton* Skeleton, const FString& OutJsonPath, const FString& ProfileName, FString& OutError);

	/** Imports motion.json as a 60 fps AnimSequence with root motion. Optionally exports FBX. */
	static FEspeonImportResult ImportMotion(const FString& MotionJsonPath, USkeleton* Skeleton,
		const FString& DestPackagePath, const FString& AssetName, const FString& OutFbxPath = FString());

	/** Exports the sequence to FBX without UI. */
	static bool ExportSequenceToFbx(UAnimSequence* Sequence, const FString& FbxPath, FString& OutError);
};
