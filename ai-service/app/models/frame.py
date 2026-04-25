from typing import Any, Literal, Optional

from pydantic import BaseModel, ConfigDict


class FramePacket(BaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)

    source_ts: int
    channel: Literal["visible", "thermal"]
    payload_ref: Optional[str] = None
    frame: Optional[Any] = None
    width: int = 0
    height: int = 0
