import os

from datetime import datetime
from dataclasses import dataclass
from typing import Any, Dict, Optional


@dataclass
class RunArgs:
    algo: str
    env: str
    seed: Optional[int] = None
    use_deterministic_algorithms: bool = True


@dataclass
class Names:
    args: RunArgs
    env_hyperparams: Dict[str, Any]
    root_dir: str
    run_id: str = datetime.now().isoformat()

    def seed(self, training: bool = True) -> Optional[int]:
        seed = self.args.seed
        if training or seed is None:
            return seed
        return seed + self.env_hyperparams.get("n_envs", 1)

    @property
    def env_id(self) -> str:
        return self.args.env

    @property
    def model_name(self) -> str:
        parts = [self.args.algo, self.env_id]
        if self.args.seed is not None:
            parts.append(f"S{self.args.seed}")
        make_kwargs = self.env_hyperparams.get("make_kwargs", {})
        if make_kwargs:
            for k, v in make_kwargs.items():
                if type(v) == bool and v:
                    parts.append(k)
                elif type(v) == int and v:
                    parts.append(f"{k}{v}")
                else:
                    parts.append(str(v))
        return "-".join(parts)

    @property
    def run_name(self) -> str:
        parts = [self.model_name, self.run_id]
        return "-".join(parts)

    @property
    def saved_models_dir(self) -> str:
        return os.path.join(self.root_dir, "saved_models")

    @property
    def downloaded_models_dir(self) -> str:
        return os.path.join(self.root_dir, "downloaded_models")

    def model_dir_name(
        self,
        best: bool = False,
        extension: str = "",
    ) -> str:
        return self.model_name + ("-best" if best else "") + extension

    def model_dir_path(self, best: bool = False, downloaded: bool = False) -> str:
        return os.path.join(
            self.saved_models_dir if not downloaded else self.downloaded_models_dir,
            self.model_dir_name(best=best),
        )

    @property
    def runs_dir(self) -> str:
        return os.path.join(self.root_dir, "runs")

    @property
    def tensorboard_summary_path(self) -> str:
        return os.path.join(self.runs_dir, self.run_name)

    @property
    def logs_path(self) -> str:
        return os.path.join(self.runs_dir, f"log.yml")

    @property
    def videos_dir(self) -> str:
        return os.path.join(self.root_dir, "videos")

    @property
    def video_prefix(self) -> str:
        return os.path.join(self.videos_dir, self.model_name)

    @property
    def best_videos_dir(self) -> str:
        return os.path.join(self.videos_dir, f"{self.model_name}-best")
