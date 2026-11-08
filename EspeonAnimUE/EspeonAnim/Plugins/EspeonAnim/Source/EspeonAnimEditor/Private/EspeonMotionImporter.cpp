#include "EspeonMotionImporter.h"

#include "Animation/AnimSequence.h"
#include "Animation/Skeleton.h"
#include "Animation/AnimData/IAnimationDataController.h"
#include "Animation/AnimData/IAnimationDataModel.h"
#include "AssetRegistry/AssetRegistryModule.h"
#include "AssetExportTask.h"
#include "Exporters/Exporter.h"
#include "Exporters/FbxExportOption.h"
#include "Dom/JsonObject.h"
#include "Dom/JsonValue.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "Policies/PrettyJsonPrintPolicy.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "HAL/FileManager.h"
#include "UObject/Package.h"
#include "UObject/SavePackage.h"
#include "Engine/SkeletalMesh.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonAnim, Log, All);

namespace
{
	const TCHAR* GProfileSkeletonName = TEXT("ue5_manny");

	FEspeonImportResult Fail(const FString& Msg)
	{
		FEspeonImportResult R;
		R.Error = FString::Printf(TEXT("EspeonAnim: %s"), *Msg);
		return R;
	}

	TSharedPtr<FJsonValue> VecJson(const FVector& V)
	{
		TArray<TSharedPtr<FJsonValue>> A;
		A.Add(MakeShared<FJsonValueNumber>(V.X));
		A.Add(MakeShared<FJsonValueNumber>(V.Y));
		A.Add(MakeShared<FJsonValueNumber>(V.Z));
		return MakeShared<FJsonValueArray>(A);
	}

	TSharedPtr<FJsonObject> TransformJson(const FTransform& T)
	{
		TSharedPtr<FJsonObject> O = MakeShared<FJsonObject>();
		O->SetField(TEXT("t"), VecJson(T.GetTranslation()));
		const FQuat Q = T.GetRotation();
		TArray<TSharedPtr<FJsonValue>> R;
		R.Add(MakeShared<FJsonValueNumber>(Q.X));
		R.Add(MakeShared<FJsonValueNumber>(Q.Y));
		R.Add(MakeShared<FJsonValueNumber>(Q.Z));
		R.Add(MakeShared<FJsonValueNumber>(Q.W));
		O->SetArrayField(TEXT("r"), R);
		O->SetField(TEXT("s"), VecJson(T.GetScale3D()));
		return O;
	}

	/** Reads a JSON array of N numbers; returns false on shape mismatch. */
	bool ReadNumbers(const TSharedPtr<FJsonValue>& V, int32 N, double* Out)
	{
		const TArray<TSharedPtr<FJsonValue>>* Arr = nullptr;
		if (!V.IsValid() || !V->TryGetArray(Arr) || Arr->Num() != N) return false;
		for (int32 i = 0; i < N; ++i)
		{
			if (!(*Arr)[i]->TryGetNumber(Out[i])) return false;
		}
		return true;
	}

	/** Rejects newer major versions; accepts any minor. */
	bool VersionOk(const FString& V)
	{
		FString Major, Rest;
		if (!V.Split(TEXT("."), &Major, &Rest)) Major = V;
		return Major == TEXT("1");
	}
}

bool UEspeonMotionImporter::DumpSkeletonProfile(USkeleton* Skeleton, const FString& OutJsonPath, const FString& ProfileName, FString& OutError)
{
	if (!Skeleton)
	{
		OutError = TEXT("EspeonAnim: null skeleton");
		return false;
	}
	const FReferenceSkeleton& Ref = Skeleton->GetReferenceSkeleton();
	const TArray<FTransform>& Pose = Ref.GetRefBonePose();
	const int32 Num = Ref.GetRawBoneNum();

	// Component space = accumulated local transforms (parents always precede children).
	TArray<FTransform> Comp;
	Comp.SetNum(Num);
	TArray<TSharedPtr<FJsonValue>> Bones;
	for (int32 i = 0; i < Num; ++i)
	{
		const int32 Parent = Ref.GetParentIndex(i);
		Comp[i] = (Parent == INDEX_NONE) ? Pose[i] : Pose[i] * Comp[Parent];

		TSharedPtr<FJsonObject> B = MakeShared<FJsonObject>();
		B->SetStringField(TEXT("name"), Ref.GetBoneName(i).ToString());
		B->SetNumberField(TEXT("parent"), Parent == INDEX_NONE ? -1 : Parent);
		B->SetObjectField(TEXT("ref_local"), TransformJson(Pose[i]));
		B->SetObjectField(TEXT("ref_component"), TransformJson(Comp[i]));
		Bones.Add(MakeShared<FJsonValueObject>(B));
	}

	TSharedPtr<FJsonObject> Root = MakeShared<FJsonObject>();
	Root->SetStringField(TEXT("format"), TEXT("espeon-skeleton"));
	Root->SetStringField(TEXT("version"), TEXT("1.0"));
	Root->SetStringField(TEXT("name"), ProfileName);
	Root->SetArrayField(TEXT("bones"), Bones);

	FString Text;
	TSharedRef<TJsonWriter<TCHAR, TPrettyJsonPrintPolicy<TCHAR>>> W = TJsonWriterFactory<TCHAR, TPrettyJsonPrintPolicy<TCHAR>>::Create(&Text);
	if (!FJsonSerializer::Serialize(Root.ToSharedRef(), W))
	{
		OutError = TEXT("EspeonAnim: JSON serialization failed");
		return false;
	}
	IFileManager::Get().MakeDirectory(*FPaths::GetPath(OutJsonPath), true);
	if (!FFileHelper::SaveStringToFile(Text, *OutJsonPath, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM))
	{
		OutError = FString::Printf(TEXT("EspeonAnim: could not write '%s'"), *OutJsonPath);
		return false;
	}
	return true;
}

FEspeonImportResult UEspeonMotionImporter::ImportMotion(const FString& MotionJsonPath, USkeleton* Skeleton,
	const FString& DestPackagePath, const FString& AssetName, const FString& OutFbxPath)
{
	if (!Skeleton) return Fail(TEXT("null skeleton"));

	// ---- Parse and validate motion.json ----
	FString Text;
	if (!FFileHelper::LoadFileToString(Text, *MotionJsonPath))
	{
		return Fail(FString::Printf(TEXT("cannot read motion file '%s'"), *MotionJsonPath));
	}
	TSharedPtr<FJsonObject> Json;
	if (!FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Json) || !Json.IsValid())
	{
		return Fail(TEXT("motion file is not valid JSON"));
	}
	if (Json->GetStringField(TEXT("format")) != TEXT("espeon-motion"))
	{
		return Fail(FString::Printf(TEXT("unexpected format '%s' (want espeon-motion)"), *Json->GetStringField(TEXT("format"))));
	}
	const FString Version = Json->GetStringField(TEXT("version"));
	if (!VersionOk(Version))
	{
		return Fail(FString::Printf(TEXT("unsupported motion version '%s' (this plugin reads 1.x)"), *Version));
	}
	const FString SkelName = Json->GetStringField(TEXT("skeleton"));
	if (SkelName != GProfileSkeletonName)
	{
		return Fail(FString::Printf(TEXT("motion targets skeleton profile '%s' but this plugin supports '%s'"), *SkelName, GProfileSkeletonName));
	}
	if (FMath::RoundToInt(Json->GetNumberField(TEXT("fps"))) != 60)
	{
		UE_LOG(LogEspeonAnim, Warning, TEXT("EspeonAnim: motion fps is %g; sequence is created at 60 fps regardless."), Json->GetNumberField(TEXT("fps")));
	}

	const FReferenceSkeleton& Ref = Skeleton->GetReferenceSkeleton();

	TArray<FName> BoneNames;
	for (const TSharedPtr<FJsonValue>& V : Json->GetArrayField(TEXT("bones")))
	{
		const FName N(*V->AsString());
		if (Ref.FindBoneIndex(N) == INDEX_NONE)
		{
			return Fail(FString::Printf(TEXT("bone '%s' from motion.json does not exist on skeleton '%s'"), *N.ToString(), *Skeleton->GetName()));
		}
		BoneNames.Add(N);
	}
	if (BoneNames.Num() == 0) return Fail(TEXT("motion.json lists no bones"));

	const TArray<TSharedPtr<FJsonValue>>& RootT = Json->GetArrayField(TEXT("root_translation"));
	const TArray<TSharedPtr<FJsonValue>>& PelvT = Json->GetArrayField(TEXT("pelvis_translation"));
	const TArray<TSharedPtr<FJsonValue>>& Rots = Json->GetArrayField(TEXT("local_rotations"));
	const int32 NumKeys = Rots.Num();
	if (NumKeys < 2) return Fail(TEXT("motion.json needs at least 2 frames"));
	if (Json->HasField(TEXT("frame_count")) && FMath::RoundToInt(Json->GetNumberField(TEXT("frame_count"))) != NumKeys)
	{
		return Fail(TEXT("frame_count does not match local_rotations length"));
	}
	if (RootT.Num() != NumKeys || PelvT.Num() != NumKeys)
	{
		return Fail(TEXT("root_translation / pelvis_translation / local_rotations frame counts differ"));
	}

	// Per-bone key arrays, initialised to the reference pose.
	TMap<FName, TArray<FVector3f>> PosKeys;
	TMap<FName, TArray<FQuat4f>> RotKeys;
	TMap<FName, TArray<FVector3f>> ScaleKeys;
	TArray<FName> TrackOrder;
	auto EnsureTrack = [&](FName N) -> bool
	{
		if (PosKeys.Contains(N)) return true;
		const int32 Idx = Ref.FindBoneIndex(N);
		if (Idx == INDEX_NONE) return false;
		const FTransform& T = Ref.GetRefBonePose()[Idx];
		PosKeys.Add(N).Init(FVector3f(T.GetTranslation()), NumKeys);
		RotKeys.Add(N).Init(FQuat4f(T.GetRotation()), NumKeys);
		ScaleKeys.Add(N).Init(FVector3f(T.GetScale3D()), NumKeys);
		TrackOrder.Add(N);
		return true;
	};
	const FName RootName(TEXT("root")), PelvisName(TEXT("pelvis"));
	if (!EnsureTrack(RootName)) return Fail(TEXT("skeleton has no 'root' bone"));
	if (!EnsureTrack(PelvisName)) return Fail(TEXT("skeleton has no 'pelvis' bone"));
	for (const FName& N : BoneNames) EnsureTrack(N);

	for (int32 F = 0; F < NumKeys; ++F)
	{
		double V[4];
		if (!ReadNumbers(RootT[F], 3, V)) return Fail(FString::Printf(TEXT("bad root_translation at frame %d"), F));
		PosKeys[RootName][F] = FVector3f((float)V[0], (float)V[1], (float)V[2]);
		if (!ReadNumbers(PelvT[F], 3, V)) return Fail(FString::Printf(TEXT("bad pelvis_translation at frame %d"), F));
		PosKeys[PelvisName][F] = FVector3f((float)V[0], (float)V[1], (float)V[2]);

		const TArray<TSharedPtr<FJsonValue>>* FrameRots = nullptr;
		if (!Rots[F]->TryGetArray(FrameRots) || FrameRots->Num() != BoneNames.Num())
		{
			return Fail(FString::Printf(TEXT("frame %d has wrong rotation count (want %d)"), F, BoneNames.Num()));
		}
		for (int32 B = 0; B < BoneNames.Num(); ++B)
		{
			if (!ReadNumbers((*FrameRots)[B], 4, V)) return Fail(FString::Printf(TEXT("bad quaternion at frame %d bone %s"), F, *BoneNames[B].ToString()));
			FQuat Q(V[0], V[1], V[2], V[3]);
			Q.Normalize();
			RotKeys[BoneNames[B]][F] = FQuat4f(Q);
		}
	}

	// ---- Create / reuse the asset ----
	const FString PackageName = DestPackagePath / AssetName;
	if (!FPackageName::IsValidLongPackageName(PackageName))
	{
		return Fail(FString::Printf(TEXT("invalid package path '%s'"), *PackageName));
	}
	UPackage* Package = CreatePackage(*PackageName);
	UAnimSequence* Seq = FindObject<UAnimSequence>(Package, *AssetName);
	if (!Seq) Seq = LoadObject<UAnimSequence>(nullptr, *(PackageName + TEXT(".") + AssetName), nullptr, LOAD_Quiet | LOAD_NoWarn);
	const bool bExisting = Seq != nullptr;
	if (!Seq)
	{
		Seq = NewObject<UAnimSequence>(Package, *AssetName, RF_Public | RF_Standalone);
	}
	Seq->SetSkeleton(Skeleton);

	IAnimationDataController& Controller = Seq->GetController();
	// UE 5.8 defaults to the Sequencer-based data model, which must be initialised (as
	// UAnimSequenceFactory does) before tracks can be added. Also resets reused assets.
	Controller.InitializeModel();
	Controller.OpenBracket(FText::FromString(TEXT("EspeonAnim import")), false);
	if (bExisting) Controller.RemoveAllBoneTracks(false);
	Controller.SetFrameRate(FFrameRate(60, 1), false);
	Controller.SetNumberOfFrames(FFrameNumber(NumKeys - 1), false);
	for (const FName& N : TrackOrder)
	{
		Controller.AddBoneCurve(N, false);
		Controller.SetBoneTrackKeys(N, PosKeys[N], RotKeys[N], ScaleKeys[N], false);
	}
	Controller.NotifyPopulated();
	Controller.CloseBracket(false);

	Seq->bEnableRootMotion = true;
	Seq->RootMotionRootLock = ERootMotionRootLock::RefPose;

	// Preview mesh: skeleton default, else the simple Manny.
	USkeletalMesh* Preview = Skeleton->GetPreviewMesh(true);
	if (!Preview)
	{
		Preview = LoadObject<USkeletalMesh>(nullptr, TEXT("/Game/Characters/Mannequins/Meshes/SKM_Manny_Simple.SKM_Manny_Simple"), nullptr, LOAD_Quiet | LOAD_NoWarn);
		if (Preview && Preview->GetSkeleton() != Skeleton) Preview = nullptr;
	}
	if (Preview) Seq->SetPreviewMesh(Preview, false);

	Seq->MarkPackageDirty();
	Seq->PostEditChange();
	if (!bExisting) FAssetRegistryModule::AssetCreated(Seq);

	// ---- Save ----
	const FString Filename = FPackageName::LongPackageNameToFilename(PackageName, FPackageName::GetAssetPackageExtension());
	FSavePackageArgs SaveArgs;
	SaveArgs.TopLevelFlags = RF_Public | RF_Standalone;
	SaveArgs.SaveFlags = SAVE_NoError;
	if (!UPackage::SavePackage(Package, Seq, *Filename, SaveArgs))
	{
		return Fail(FString::Printf(TEXT("failed to save package '%s'"), *Filename));
	}

	FEspeonImportResult R;
	R.bSuccess = true;
	R.PackageName = PackageName;

	if (!OutFbxPath.IsEmpty())
	{
		FString Err;
		if (!ExportSequenceToFbx(Seq, OutFbxPath, Err))
		{
			R.bSuccess = false;
			R.Error = Err;
			return R;
		}
		R.FbxPath = OutFbxPath;
	}
	UE_LOG(LogEspeonAnim, Display, TEXT("EspeonAnim: created %s (%d keys @ 60 fps, %d tracks)"), *PackageName, NumKeys, TrackOrder.Num());
	return R;
}

bool UEspeonMotionImporter::ExportSequenceToFbx(UAnimSequence* Sequence, const FString& FbxPath, FString& OutError)
{
	if (!Sequence)
	{
		OutError = TEXT("EspeonAnim: null sequence for FBX export");
		return false;
	}
	if (!Sequence->GetPreviewMesh())
	{
		OutError = TEXT("EspeonAnim: FBX export needs a preview skeletal mesh on the sequence/skeleton, none found");
		return false;
	}
	IFileManager::Get().MakeDirectory(*FPaths::GetPath(FbxPath), true);

	UAssetExportTask* Task = NewObject<UAssetExportTask>();
	Task->AddToRoot();
	Task->Object = Sequence;
	Task->Filename = FbxPath;
	Task->bAutomated = true;
	Task->bPrompt = false;
	Task->bReplaceIdentical = true;
	Task->bSelected = false;
	Task->bUseFileArchive = false;
	Task->Options = NewObject<UFbxExportOption>();

	const bool bOk = UExporter::RunAssetExportTask(Task);
	Task->RemoveFromRoot();

	if (!bOk || IFileManager::Get().FileSize(*FbxPath) <= 0)
	{
		OutError = FString::Printf(TEXT("EspeonAnim: FBX export failed for '%s'"), *FbxPath);
		return false;
	}
	return true;
}
