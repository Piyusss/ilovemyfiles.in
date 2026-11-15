import { describe, it, expect, vi, beforeEach } from 'vitest';
import { chunkedUpload } from '../utils/chunkedUpload';
import * as api from '../api';
import * as transfer from '../api/transfer';

vi.mock('../api', () => ({
  chunkApi: { init: vi.fn(), complete: vi.fn(), cancel: vi.fn() },
}));
vi.mock('../api/transfer', () => ({ uploadPart: vi.fn() }));

const file = () => new File(['hello world'], 'notes.txt', { type: 'text/plain' });
const start = () => chunkedUpload(file(), undefined, () => {}, new AbortController().signal);

describe('chunkedUpload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.chunkApi.init).mockResolvedValue({
      data: { uploadId: 'up-1', parts: [{ partNumber: 1, url: 'https://storage/part-1' }], duplicate: false },
    } as never);
    vi.mocked(api.chunkApi.cancel).mockResolvedValue({} as never);
  });

  // A failed upload used to leave its session open, holding its size against
  // the account's quota for a day.
  it('releases the server-side upload when a part keeps failing', async () => {
    vi.mocked(transfer.uploadPart).mockRejectedValue(new Error('Network Error'));

    await expect(start()).rejects.toThrow('Network Error');
    expect(api.chunkApi.cancel).toHaveBeenCalledWith('up-1');
  });

  it('releases the server-side upload when completing fails', async () => {
    vi.mocked(transfer.uploadPart).mockResolvedValue({} as never);
    vi.mocked(api.chunkApi.complete).mockRejectedValue(new Error('size mismatch'));

    await expect(start()).rejects.toThrow('size mismatch');
    expect(api.chunkApi.cancel).toHaveBeenCalledWith('up-1');
  });

  it('leaves a finished upload alone', async () => {
    vi.mocked(transfer.uploadPart).mockResolvedValue({} as never);
    vi.mocked(api.chunkApi.complete).mockResolvedValue({ data: { id: 'file-1' } } as never);

    await expect(start()).resolves.toEqual({ id: 'file-1' });
    expect(api.chunkApi.cancel).not.toHaveBeenCalled();
  });
});
