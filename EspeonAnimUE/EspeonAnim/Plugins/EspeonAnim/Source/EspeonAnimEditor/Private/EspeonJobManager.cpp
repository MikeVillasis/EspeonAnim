#include "EspeonJobManager.h"
#include "EspeonAnimSettings.h"
#include "EspeonMotionImporter.h"
#include "EspeonProcessorSetup.h"

#include "Animation/Skeleton.h"
#include "Async/Async.h"
#include "Dom/JsonObject.h"
#include "FileUtilities/ZipArchiveReader.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformFileManager.h"
#include "HAL/PlatformMisc.h"
#include "HAL/PlatformProcess.h"
#include "Misc/DateTime.h"
#include "Misc/FileHelper.h"
#include "Misc/Guid.h"
#include "Misc/Paths.h"
#include "ObjectTools.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonJob, Log, All);

const TCHAR* EspeonJobStateToString(EEspeonJobState State)
{
	switch (State)
	{
	case EEspeonJobState::Received:   return TEXT("received");
	case EEspeonJobState::Processing: return TEXT("processing");
	case EEspeonJobState::Importing:  return TEXT("importing");
	case EEspeonJobState::Done:       return TEXT("done");
	case EEspeonJobState::Failed:     return TEXT("failed");
	}
	return TEXT("failed");
}

namespace
{
	/** Share of the whole-job progress bar owned by the processor; import + FBX take the rest. */
	constexpr float ProcessorShare = 0.85f;

	TSharedPtr<FJsonObject> ParseJson(const FString& Text)
	{
		TSharedPtr<FJsonObject> Obj;
		if (!FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Obj)) return nullptr;
		return Obj;
	}

	/** Reads "action" from manifest.json inside a .espeon zip; empty on any failure. */
	FString ReadActionFromPackage(const FString& PackagePath)
	{
		IFileHandle* Handle = FPlatformFileManager::Get().GetPlatformFile().OpenRead(*PackagePath);
		if (!Handle) return FString();
		FZipArchiveReader Zip(Handle); // takes ownership of Handle
		if (!Zip.IsValid()) return FString();
		TArray<uint8> Data;
		if (!Zip.TryReadFile(TEXT("manifest.json"), Data)) return FString();
		FString Text;
		FFileHelper::BufferToString(Text, Data.GetData(), Data.Num());
		if (TSharedPtr<FJsonObject> M = ParseJson(Text))
		{
			FString Action;
			M->TryGetStringField(TEXT("action"), Action);
			return Action;
		}
		return FString();
	}
}

FEspeonJobManager& FEspeonJobManager::Get()
{
	static FEspeonJobManager Instance;
	return Instance;
}

void FEspeonJobManager::Update(const FString& Id, TFunctionRef<void(FJob&)> Fn)
{
	FScopeLock L(&Lock);
	if (FJob* J = Jobs.Find(Id)) Fn(*J);
}

void FEspeonJobManager::SetProgress(const FString& Id, EEspeonJobState State, float Progress, const FString& Message)
{
	Update(Id, [&](FJob& J)
	{
		J.S.State = State;
		J.S.Progress = FMath::Clamp(Progress, 0.f, 1.f);
		if (!Message.IsEmpty()) J.S.Message = Message;
	});
}

void FEspeonJobManager::Fail(const FString& Id, const FString& Message)
{
	UE_LOG(LogEspeonJob, Error, TEXT("EspeonAnim job %s failed: %s"), *Id, *Message);
	Update(Id, [&](FJob& J)
	{
		J.S.State = EEspeonJobState::Failed;
		J.S.Message = Message;
	});
}

bool FEspeonJobManager::CreateJob(const FString& InputExtension, const FString& SourceName, FString& OutId, FString& OutInputPath, FString& OutError)
{
	const UEspeonAnimSettings* Settings = UEspeonAnimSettings::Get();
	const FString Id = FGuid::NewGuid().ToString(EGuidFormats::Digits).Left(12).ToLower();
	const FString JobDir = FPaths::Combine(Settings->GetResolvedJobsDir(), Id);
	if (!IFileManager::Get().MakeDirectory(*JobDir, true))
	{
		OutError = FString::Printf(TEXT("Cannot create job folder '%s'"), *JobDir);
		return false;
	}
	FString Ext = InputExtension.StartsWith(TEXT(".")) ? InputExtension.ToLower() : TEXT(".") + InputExtension.ToLower();
	if (Ext == TEXT(".")) Ext = TEXT(".espeon");

	FJob Job;
	Job.S.Id = Id;
	Job.S.State = EEspeonJobState::Received;
	Job.S.Message = TEXT("Received");
	Job.S.JobDir = JobDir;
	Job.S.InputPath = FPaths::Combine(JobDir, TEXT("input") + Ext);
	Job.S.CreatedUtc = FDateTime::UtcNow();
	Job.SourceName = SourceName;
	OutId = Id;
	OutInputPath = Job.S.InputPath;
	{
		FScopeLock L(&Lock);
		Jobs.Add(Id, MoveTemp(Job));
	}
	return true;
}

bool FEspeonJobManager::CreateJobFromFile(const FString& SourceFile, FString& OutId, FString& OutError)
{
	if (!IFileManager::Get().FileExists(*SourceFile))
	{
		OutError = FString::Printf(TEXT("Input file not found: %s"), *SourceFile);
		return false;
	}
	FString InputPath;
	if (!CreateJob(FPaths::GetExtension(SourceFile), FPaths::GetBaseFilename(SourceFile), OutId, InputPath, OutError)) return false;
	if (IFileManager::Get().Copy(*InputPath, *SourceFile) != COPY_OK)
	{
		OutError = TEXT("Could not copy the input into the job folder");
		Fail(OutId, OutError);
		return false;
	}
	return true;
}

void FEspeonJobManager::SetNameOverride(const FString& Id, const FString& AssetName)
{
	Update(Id, [&](FJob& J) { J.NameOverride = AssetName; });
}

bool FEspeonJobManager::GetJob(const FString& Id, FEspeonJobStatus& OutStatus) const
{
	FScopeLock L(&Lock);
	if (const FJob* J = Jobs.Find(Id))
	{
		OutStatus = J->S;
		return true;
	}
	return false;
}

TArray<FEspeonJobStatus> FEspeonJobManager::GetJobs() const
{
	TArray<FEspeonJobStatus> Out;
	{
		FScopeLock L(&Lock);
		for (const auto& P : Jobs) Out.Add(P.Value.S);
	}
	Out.Sort([](const FEspeonJobStatus& A, const FEspeonJobStatus& B) { return A.CreatedUtc > B.CreatedUtc; });
	return Out;
}

void FEspeonJobManager::Shutdown()
{
	bShuttingDown = true;
}

void FEspeonJobManager::RunAsync(const FString& Id)
{
	Async(EAsyncExecution::ThreadPool, [this, Id]()
	{
		FString Motion, Report;
		if (!RunProcessor(Id, Motion, Report)) return;
		AsyncTask(ENamedThreads::GameThread, [this, Id, Motion, Report]()
		{
			RunImport(Id, Motion, Report);
		});
	});
}

bool FEspeonJobManager::RunSync(const FString& Id)
{
	FString Motion, Report;
	if (RunProcessor(Id, Motion, Report))
	{
		RunImport(Id, Motion, Report);
	}
	FEspeonJobStatus S;
	return GetJob(Id, S) && S.State == EEspeonJobState::Done;
}

bool FEspeonJobManager::RunProcessor(const FString& Id, FString& OutMotionPath, FString& OutReportPath)
{
	FEspeonJobStatus Job;
	if (!GetJob(Id, Job)) return false;

	const UEspeonAnimSettings* Settings = UEspeonAnimSettings::Get();
	const FString Python = Settings->GetResolvedPython();
	const FString WorkDir = Settings->GetResolvedWorkingDir();
	if (!FPaths::FileExists(Python))
	{
		UE_LOG(LogEspeonJob, Warning, TEXT("Processor python not found at '%s'"), *Python);
		Fail(Id, FEspeonProcessorSetup::Get().IsInstalling() ? TEXT("Processor installation is still running - wait for it to finish, then import again") : TEXT("Processor not installed - open the EspeonAnim Capture tab and click Install / Repair processor"));
		return false;
	}

	// One processor at a time: each run needs ~4-5 GB of VRAM, so parallel jobs would risk OOM.
	static FCriticalSection GProcessorLock;
	SetProgress(Id, EEspeonJobState::Processing, 0.f, TEXT("Waiting for the previous job to finish"));
	FScopeLock ProcessorLock(&GProcessorLock);

	const FString OutDir = FPaths::Combine(Job.JobDir, TEXT("out"));
	IFileManager::Get().MakeDirectory(*OutDir, true);
	const FString LogFilePath = FPaths::Combine(Job.JobDir, TEXT("processor.log"));
	TUniquePtr<FArchive> LogFile(IFileManager::Get().CreateFileWriter(*LogFilePath, FILEWRITE_AllowRead));

	auto LogLine = [&LogFile](const FString& Line)
	{
		if (!LogFile) return;
		FTCHARToUTF8 Utf8(*(Line + TEXT("\n")));
		LogFile->Serialize((void*)Utf8.Get(), Utf8.Length());
		LogFile->Flush();
	};

	const FString Args = FString::Printf(
		TEXT("-m espeon_processor process --input \"%s\" --out \"%s\" --skeleton ue5_manny"),
		*Job.InputPath, *OutDir);
	LogLine(FString::Printf(TEXT("> %s %s  (cwd %s)"), *Python, *Args, *WorkDir));

	FPlatformMisc::SetEnvironmentVar(TEXT("PYTHONUNBUFFERED"), TEXT("1"));
	FPlatformMisc::SetEnvironmentVar(TEXT("PYTHONUTF8"), TEXT("1"));

	void* OutRead = nullptr; void* OutWrite = nullptr;
	void* ErrRead = nullptr; void* ErrWrite = nullptr;
	FPlatformProcess::CreatePipe(OutRead, OutWrite);
	FPlatformProcess::CreatePipe(ErrRead, ErrWrite);
	uint32 Pid = 0;
	FProcHandle Proc = FPlatformProcess::CreateProc(*Python, *Args, false, true, true, &Pid, 0,
		WorkDir.IsEmpty() ? nullptr : *WorkDir, OutWrite, nullptr, ErrWrite);
	if (!Proc.IsValid())
	{
		FPlatformProcess::ClosePipe(OutRead, OutWrite);
		FPlatformProcess::ClosePipe(ErrRead, ErrWrite);
		Fail(Id, FString::Printf(TEXT("Could not launch the processor (%s)"), *Python));
		return false;
	}
	SetProgress(Id, EEspeonJobState::Processing, 0.f, TEXT("Starting processor"));

	FString OutBuf, ErrBuf, LastError, DoneMotion, DoneReport;
	auto HandleOutLine = [&](const FString& Line)
	{
		const FString T = Line.TrimStartAndEnd();
		if (T.IsEmpty()) return;
		TSharedPtr<FJsonObject> Obj = T.StartsWith(TEXT("{")) ? ParseJson(T) : nullptr;
		FString Event;
		if (!Obj || !Obj->TryGetStringField(TEXT("event"), Event))
		{
			LogLine(TEXT("[stdout] ") + T);
			return;
		}
		if (Event == TEXT("progress"))
		{
			double P = 0; FString Msg;
			Obj->TryGetNumberField(TEXT("progress"), P);
			Obj->TryGetStringField(TEXT("message"), Msg);
			SetProgress(Id, EEspeonJobState::Processing, float(P) * ProcessorShare, Msg);
		}
		else if (Event == TEXT("done"))
		{
			Obj->TryGetStringField(TEXT("motion"), DoneMotion);
			Obj->TryGetStringField(TEXT("report"), DoneReport);
		}
		else if (Event == TEXT("error"))
		{
			Obj->TryGetStringField(TEXT("message"), LastError);
		}
		LogLine(TEXT("[event] ") + T);
	};
	auto Drain = [&]()
	{
		OutBuf += FPlatformProcess::ReadPipe(OutRead);
		ErrBuf += FPlatformProcess::ReadPipe(ErrRead);
		int32 Idx;
		while (OutBuf.FindChar(TEXT('\n'), Idx)) { HandleOutLine(OutBuf.Left(Idx)); OutBuf.RightChopInline(Idx + 1); }
		while (ErrBuf.FindChar(TEXT('\n'), Idx)) { LogLine(TEXT("[stderr] ") + ErrBuf.Left(Idx).TrimEnd()); ErrBuf.RightChopInline(Idx + 1); }
	};

	while (FPlatformProcess::IsProcRunning(Proc))
	{
		if (bShuttingDown)
		{
			FPlatformProcess::TerminateProc(Proc, true);
			break;
		}
		Drain();
		FPlatformProcess::Sleep(0.03f);
	}
	Drain();
	if (!OutBuf.IsEmpty()) HandleOutLine(OutBuf);
	if (!ErrBuf.IsEmpty()) LogLine(TEXT("[stderr] ") + ErrBuf);

	int32 ReturnCode = -1;
	FPlatformProcess::GetProcReturnCode(Proc, &ReturnCode);
	FPlatformProcess::CloseProc(Proc);
	FPlatformProcess::ClosePipe(OutRead, OutWrite);
	FPlatformProcess::ClosePipe(ErrRead, ErrWrite);
	LogLine(FString::Printf(TEXT("[exit] code %d"), ReturnCode));
	LogFile.Reset();

	auto Resolve = [&](const FString& P, const TCHAR* Default)
	{
		FString R = P.IsEmpty() ? FPaths::Combine(OutDir, Default) : P;
		if (FPaths::IsRelative(R)) R = FPaths::Combine(OutDir, R);
		return R;
	};
	const FString Motion = Resolve(DoneMotion, TEXT("motion.json"));
	const FString Report = Resolve(DoneReport, TEXT("report.json"));

	if (ReturnCode != 0 || !FPaths::FileExists(Motion))
	{
		const FString Reason = !LastError.IsEmpty() ? LastError
			: ReturnCode != 0 ? FString::Printf(TEXT("Processor exited with code %d"), ReturnCode)
			: FString(TEXT("Processor finished without writing motion.json"));
		Fail(Id, FString::Printf(TEXT("%s (log: %s)"), *Reason, *LogFilePath));
		return false;
	}
	OutMotionPath = Motion;
	OutReportPath = Report;
	SetProgress(Id, EEspeonJobState::Processing, ProcessorShare, TEXT("Processing finished"));
	return true;
}

void FEspeonJobManager::RunImport(const FString& Id, const FString& MotionPath, const FString& ReportPath)
{
	check(IsInGameThread());
	FEspeonJobStatus Job;
	FString NameOverride, SourceName;
	{
		FScopeLock L(&Lock);
		const FJob* J = Jobs.Find(Id);
		if (!J) return;
		Job = J->S; NameOverride = J->NameOverride; SourceName = J->SourceName;
	}
	SetProgress(Id, EEspeonJobState::Importing, ProcessorShare, TEXT("Importing animation"));

	// Report: quality + warnings (shown to the phone and in the UI).
	FString ReportText;
	if (FFileHelper::LoadFileToString(ReportText, *ReportPath))
	{
		if (TSharedPtr<FJsonObject> R = ParseJson(ReportText))
		{
			FString Quality;
			R->TryGetStringField(TEXT("quality"), Quality);
			TArray<FEspeonJobWarning> Warnings;
			const TArray<TSharedPtr<FJsonValue>>* Arr = nullptr;
			if (R->TryGetArrayField(TEXT("warnings"), Arr))
			{
				for (const TSharedPtr<FJsonValue>& V : *Arr)
				{
					const TSharedPtr<FJsonObject>* O = nullptr;
					if (!V.IsValid() || !V->TryGetObject(O)) continue;
					FEspeonJobWarning W;
					(*O)->TryGetStringField(TEXT("code"), W.Code);
					(*O)->TryGetStringField(TEXT("message"), W.Message);
					(*O)->TryGetStringField(TEXT("suggestion"), W.Suggestion);
					Warnings.Add(MoveTemp(W));
				}
			}
			Update(Id, [&](FJob& J) { J.S.Quality = Quality; J.S.Warnings = MoveTemp(Warnings); });
		}
	}

	const UEspeonAnimSettings* Settings = UEspeonAnimSettings::Get();
	USkeleton* Skeleton = Settings->TargetSkeleton.LoadSynchronous();
	if (!Skeleton)
	{
		Fail(Id, FString::Printf(TEXT("Target skeleton not found: %s"), *Settings->TargetSkeleton.ToString()));
		return;
	}

	// Asset name: AS_<action>_<yyyymmdd_hhmmss>. The action comes from manifest.json inside a package,
	// or from the file name for a bare video.
	FString AssetName = NameOverride;
	if (AssetName.IsEmpty())
	{
		FString Action;
		if (Job.InputPath.EndsWith(TEXT(".espeon"))) Action = ReadActionFromPackage(Job.InputPath);
		if (Action.IsEmpty()) Action = SourceName;
		Action = ObjectTools::SanitizeObjectName(Action);
		if (Action.IsEmpty()) Action = TEXT("motion");
		AssetName = FString::Printf(TEXT("AS_%s_%s"), *Action, *Job.CreatedUtc.ToString(TEXT("%Y%m%d_%H%M%S")));
	}
	AssetName = ObjectTools::SanitizeObjectName(AssetName);

	FString FbxPath;
	if (Settings->bExportFbx)
	{
		FbxPath = FPaths::Combine(Settings->GetResolvedFbxDir(), AssetName + TEXT(".fbx"));
	}
	Update(Id, [&](FJob& J) { J.S.Name = AssetName; });
	SetProgress(Id, EEspeonJobState::Importing, 0.9f, TEXT("Creating AnimSequence and FBX"));

	FEspeonImportResult R = UEspeonMotionImporter::ImportMotion(MotionPath, Skeleton,
		Settings->GetResolvedDestinationPath(), AssetName, FbxPath);
	if (!R.PackageName.IsEmpty())
	{
		Update(Id, [&](FJob& J) { J.S.AnimAssetPath = R.PackageName; J.S.FbxPath = R.FbxPath; });
	}
	if (!R.bSuccess)
	{
		Fail(Id, R.Error.IsEmpty() ? FString(TEXT("Import failed")) : R.Error);
		return;
	}
	Update(Id, [&](FJob& J)
	{
		J.S.State = EEspeonJobState::Done;
		J.S.Progress = 1.f;
		J.S.Message = FString::Printf(TEXT("Imported %s"), *R.PackageName);
	});
	UE_LOG(LogEspeonJob, Display, TEXT("EspeonAnim job %s done: %s"), *Id, *R.PackageName);
}
