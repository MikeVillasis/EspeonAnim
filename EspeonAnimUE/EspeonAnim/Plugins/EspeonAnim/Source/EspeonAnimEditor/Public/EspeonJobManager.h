#pragma once

#include "CoreMinimal.h"
#include "HAL/CriticalSection.h"
#include "Templates/Atomic.h"

enum class EEspeonJobState : uint8
{
	Received,
	Processing,
	Importing,
	Done,
	Failed
};

ESPEONANIMEDITOR_API const TCHAR* EspeonJobStateToString(EEspeonJobState State);

struct FEspeonJobWarning
{
	FString Code;
	FString Message;
	FString Suggestion;
};

/** Snapshot of one job (copied out under a lock, safe to use on any thread). */
struct FEspeonJobStatus
{
	FString Id;
	FString Name;               // asset name once known
	EEspeonJobState State = EEspeonJobState::Received;
	float Progress = 0.f;       // 0..1 over the whole job
	FString Message;
	FString Quality;            // good | usable | poor (from report.json)
	TArray<FEspeonJobWarning> Warnings;
	FString AnimAssetPath;      // /Game/... package path of the created AnimSequence
	FString FbxPath;
	FString JobDir;
	FString InputPath;
	FDateTime CreatedUtc;
};

/**
 * A job is a package (.espeon) or video file on disk: JobsDir/<id>/input.<ext>. The processor runs as a child
 * process (docs/formats.md section 6) on a worker thread; the import + FBX export then run on the game thread.
 * All state lives in a mutex-protected map so the HTTP receiver, the UI and commandlets can read it from anywhere.
 */
class ESPEONANIMEDITOR_API FEspeonJobManager
{
public:
	static FEspeonJobManager& Get();

	/** Allocates a job folder and returns the path the caller must write the input to. State = received. */
	bool CreateJob(const FString& InputExtension, const FString& SourceName, FString& OutId, FString& OutInputPath, FString& OutError);

	/** Copies an existing file into a new job. */
	bool CreateJobFromFile(const FString& SourceFile, FString& OutId, FString& OutError);

	/** Overrides the generated asset name (AS_<action>_<timestamp>) for a job. */
	void SetNameOverride(const FString& Id, const FString& AssetName);

	/** Runs the processor on a thread-pool thread, then the import on the game thread. Returns immediately. */
	void RunAsync(const FString& Id);

	/** Runs the whole job on the calling thread, which must be the game thread. Returns true on state == done. */
	bool RunSync(const FString& Id);

	bool GetJob(const FString& Id, FEspeonJobStatus& OutStatus) const;
	TArray<FEspeonJobStatus> GetJobs() const;

	/** Stops running processors (editor shutdown). */
	void Shutdown();

private:
	struct FJob
	{
		FEspeonJobStatus S;
		FString SourceName;
		FString NameOverride;
	};

	bool RunProcessor(const FString& Id, FString& OutMotionPath, FString& OutReportPath);
	void RunImport(const FString& Id, const FString& MotionPath, const FString& ReportPath);
	void Update(const FString& Id, TFunctionRef<void(FJob&)> Fn);
	void SetProgress(const FString& Id, EEspeonJobState State, float Progress, const FString& Message);
	void Fail(const FString& Id, const FString& Message);

	mutable FCriticalSection Lock;
	TMap<FString, FJob> Jobs;
	TAtomic<bool> bShuttingDown{ false };
};
